

# Introduction #

Coroutines instrumentation uses excellent [ASM](http://asm.ow2.org/) library.

Instrumentation is done in two phases. The first one is carried out to determine which methods in a class being loaded are marked as coroutines. It is fired for every loaded class if _runtime_ option is specified or for user specified classes only otherwise. It discovers instances of `Coroutine` annotation and marks which methods are annotated and need to be instrumented. It also performs basic sanity checks to see whether method is suitable to become coroutine: it must return `CoIterator` and not be abstract. If no such methods are found in the class there is no more work for the agent and it submits class unchanged to JVM runtime.
If any coroutine method is discovered the second phase begins. In this phase coroutine semantics are added to method(s) and `CoIterator` implementation(s) bound to coroutines are created.

# Coroutine methods #

All methods marked as coroutines in the previous phase are processed one by one. Processing starts with generating new public and final method which name is mangled original method name, so that no name collisions occur. The new method always returns an object and takes three formal arguments: `Frame` and two `Objects`. `mr.go.coroutines.core.Frame` is container in which coroutine execution state is hold (local variables value and operand stack contents) along with an integer saying where to jump on _next_/_send_ (it is -1 for closed coroutine, 0 for the first execution, 1 for the first _yield_ in method code, and so on). It also keeps names of local variables and line of code where recent yield took place for debugging purposes. To make use of this information your code has to be compiled with debug support and agent `debug` option needs to be turned on.


At the beginning of every coroutine method **preamble** is generated. In pseudo-code:
```
int state = frame.getState(); 
Object[] locals = frame.getLocals();
if (chkclosed) throw closed;
switch(state)
{case 0: break; 
...
case n: goto yield;
default: throw invalid;
} 
arg1, arg2, arg3 = locals[i..j] 
if (chkexit) throw closed;
```

**Preamble** creates two additional local variables holding coroutine current state and an array of original local variables and their current values. Next, it checks whether state is equal to -1, if it is that means that coroutine have been closed and `CoroutineClosedException` is thrown. Then it finds an instruction where execution is suspended and jumps there unless `state == 0`. If state is 0 that means that this is the first execution of coroutine so there is really no _yield_ to jump to. In that case only local arguments passed to coroutine are restored and special check is generated which throws `CoroutineClosedException` if the caller closed this coroutine before it had a chance to execute.
Next, all user code is emitted up to the yield point, except that return statements and the _`_method`_ are treated in special way. Returns are simply removed, `NoSuchElementException` is emitted instead, _`_method`_ is replaced by no-op. All changes of variables positions caused by coroutine locals and arguments are taken into account. This procedure is repeated till the end of method instruction stream.

## Yielding ##

When call to `mr.go.coroutines.user.Coroutines.yield` is detected it is removed completely and replacement bytecode is emitted. Instructions which override `yield` store local variables and stack in the `Frame` container, update `Frame` state variable, resolve jump instruction offset from **preamble** _case_ statement, store the top element on the operand stack in the third local variable and emit jump instruction to **prologue** code. **Prologue** code's only task is to return reference from the third local variable, it is created after all user code instructions have been processed.

In _debug_ mode line of code that corresponds to the current yield is also stored in `Frame` for logging purposes.

Because `Frame` must store every reference as an object reference, it is essential to know approximate runtime type of every local variable and operand on the stack during yielding operation to perform appropriate typecasting and unboxing on restore and boxing on save. Fundamental **assertion of yield** is that types and values of local variables and operands have to be identical before and after any yield. Fortunately, these can be deduced from instruction stream and original method descriptor. Every instruction that mutates operand stack is simulated on _internal_ temporary stack (_internal_ stack contains only deduced type information) and for every transformed method structures containing deduced variable info are maintained. With help of these, appropriate type casts are introduced in bytecode.

In the next step instructions that have to be executed on resume are created. These are: checks whether closing flag is set and throwing `CoroutineExitException` if it is, restoring stack and local variables and pushing the second local variable onto the stack. The second local variable holds reference to a value _sent_ to coroutine by the caller via `CoIterator`'s _send_ method. This bytecode organization assures that whenever coroutine is resumed **assertion of yield** holds true.

## Stack map table ##

The real problem in implementing verifiable coroutine code lies in maintaining appropriate _stack map tables_. This task is further complicated by the fact that _stack map tables_ in Java 6 class format are stored in forms of _deltas_ from previous stack map table encountered. Because every _yield_ is a target of unconditional goto (see **preamble**) then valid stack map needs to be generated at yield's offset. The only valid stack map at that point must be stripped of all original local variables, because they are unknown at the moment. That means that the original frame following _yield_'s become invalid, as the new frame has been introduced between this frame and its base frame. The only way is to modify the following frame using gathered variable information. This process is called _frame merging_ in the code. Because of **assertion of yield** only the one subsequent frame has to be merged, unless it is frame at the beginning of a try-catch handler block which must be treated in a different way.

Stack map tables are furthermore vital to perform certain optimizations. See [#Optimizations](#Optimizations.md).

It is not certain currently that transformations described here are valid for _every_ valid Java 6 code. The ultimate goal is to correct all emerging bugs. If you came across a code that's unverifiable you may try _overrideframes_ option. This option undoes above algorithm and uses ASM ClassWriter that tries to compute _stack map table_ using its algorithm. It sometimes helps to get things going before bug is fixed. Using this option induces performance price because ASM algorithm needs to be generic while coroutines instrumentation layer is able to reuse information which compiler produced with only slight modifications when needed.

## Exception handlers ##

If _yield_ is encountered in the middle of a try-catch block it poses certain problem when it comes to _stack map tables_. It is required that every handler of a try-catch block is accompanied with valid stack map and every instruction within try-catch block must be valid with respect to handler's map. that effectively requires modification of original handler's map so that it does not take local variables of original method into account, because there is no guarantee that they have been properly restored before exception occurred. But such modification makes exception handler code unverifiable, because all original local variables are deemed uninitialized by the Java verifier. To overcome the problem code that restores all active variables is required at the beginning of handler code. Modification of handler's map causes yet another change to _deltas_ of following maps, so the next map has to be merged using _frame merging_ algorithm.

To handle try-catch blocks additional data structure describing these blocks is maintained during instrumentation. It holds addresses of block start, end and handler code along with list of variables that were initialized before a block started (as they are the only variables that can be used in the handler). If _yield_ is detected within try-catch block it is flagged and all procedures mentioned in the first paragraph are carried out on handler's entry.

## Stub method ##

After coroutine's 'real' method has been completed original method is changed to _coroutine stub_. Using information obtained during instrumentation stage agent is able to create `CoIterator` implementation which is bound to freshly transformed method. `CoIterator` is written to disk in form of a class file and instantiated in stub method. Stub creates also unique `Frame` instance which it then passes to `CoIterator` and emits logging code in debug mode. `Frame` is initialized with local arguments and _this_ reference (if the method is not static). Stub returns `CoIterator` to the caller.


# Optimizations #

## Local variables copying ##

Every yield causes local variables to be copied to `Frame` container to satisfy **yield assertion**. Yet clearly, if we are certain that some variable's value could not be modified between subsequent calls to _yield_ then **yield assertion** will hold regardless of whether variable is copied or not. Holding to this observation, instrumenting layer can avoid unneeded copying effort.
In order for this optimization to be implemented properly two pieces of information must be present: _variable state_ and _stack map table_. The latter is generated by a compiler and is always present, the former is maintained by instrumenting layer.

Stack map provides 'block' information - which variable goes out of scope and which outlives block of code. Maintained in-memory variable state tells whether variable has been initialized, changed or copied to `Frame`.

Variable is assumed to be in one of the three states: temporary, initialized and final. Every store instruction makes variable initialized and not final. Every initialized occurrence of variable in _stack map table_ makes it not temporary. Every uninitialized occurrence makes it uninitialized. If not temporary variable is copied to `Frame` it becomes final from now on. Only variables which are not final and initialized need to be copied to `Frame` before _yield_. At the beginning of a method its actual arguments are considered not temporary and final.

Let's see how it works in practice. We are going to examine grep coroutine. In the code listing presented below all places where stack map table information is present are marked
```
	@Coroutine
	public static CoIterator<String, Void> grep(String pattern, String file)
			throws IOException {
		FileReader freader = new FileReader(file);
		BufferedReader reader = new BufferedReader(freader);
		String line;
		Pattern regex = Pattern.compile(pattern);
		try {
			while (
//stack map 1
                              (line = reader.readLine()) != null) {
				boolean isFound = regex.matcher(line).find();
				if (isFound) {
					yield(line); (1)
				}
			}
//stack map 2
		} catch (CoroutineExitException e) {
//stack map 4
			reader.close();
			throw e;
		}
		yield(null); (2)
		reader.close();
		return _();
	}
```

Stack map 1 marks `freader`, `reader`, `line` and `regex` as not temporary. They are initialized as well because of assignments except for `line` which is uninitialized currently. `File` and `pattern` are final because they are actual arguments of this method.

At (1) yield takes place. All local variables except method arguments must be copied (`line` has become initialized at this point) because all local variables are not final. There is no need to copy arguments. All variables except `isFound` have been not temporary at the time of yield so they become final right after.

At Stack map 2 `line` and `isFound` go out of scope.

At the beginning of exception handler only active variables have to be restored. That includes: `file`, `pattern`, `freader`, `reader` and `regex`

At (2) another yield takes place. Because all local variables are either final or uninitialized nothing needs to be copied to `Frame`.