package pl.clareo.coroutines.core.asm;

/***
 * ASM: a very small and fast Java bytecode manipulation framework
 * Copyright (c) 2000-2007 INRIA, France Telecom
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holders nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGE.
 */
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

import pl.clareo.coroutines.core.CoroutineGenerationException;

/**
 * A semantic bytecode analyzer. <i>This class does not fully check that JSR and
 * RET instructions are valid.</i>
 * 
 * This class has been adapted for the purpose of coroutines from the ASM
 * library. It makes use of stack map frames during analyze and all subroutine
 * processing code has been removed.
 * 
 * @author Eric Bruneton
 */
public class Analyzer implements Opcodes {

    private static Type getTypeFromFrameOpcode(Object opcode) {
        if (opcode instanceof String) {
            return Type.getObjectType((String) opcode);
        }
        if (opcode == INTEGER) {
            return Type.INT_TYPE;
        }
        if (opcode == FLOAT) {
            return Type.FLOAT_TYPE;
        }
        if (opcode == LONG) {
            return Type.LONG_TYPE;
        }
        if (opcode == DOUBLE) {
            return Type.DOUBLE_TYPE;
        }
        if (opcode == NULL) {
            return Type.VOID_TYPE;
        }
        return null;
    }

    private Frame[]                   frames;
    private List<TryCatchBlockNode>[] handlers;
    private InsnList                  insns;
    private final Interpreter         interpreter;
    private int                       n;
    private int[]                     queue;
    private boolean[]                 queued;
    private int                       stackMapTop;
    // private Subroutine[] subroutines;
    private int                       top;

    /**
     * Constructs a new {@link Analyzer}.
     * 
     * @param interpreter
     *            the interpreter to be used to symbolically interpret the
     *            bytecode instructions.
     */
    public Analyzer(final Interpreter interpreter) {
        this.interpreter = interpreter;
    }

    /**
     * Analyzes the given method.
     * 
     * @param owner
     *            the internal name of the class to which the method belongs.
     * @param m
     *            the method to be analyzed.
     * @return the symbolic state of the execution stack frame at each bytecode
     *         instruction of the method. The size of the returned array is
     *         equal to the number of instructions (and labels) of the method. A
     *         given frame is <tt>null</tt> if and only if the corresponding
     *         instruction cannot be reached (dead code).
     * @throws AnalyzerException
     *             if a problem occurs during the analysis.
     */
    public Frame[] analyze(final String owner, final MethodNode m) throws AnalyzerException {
        if ((m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
            frames = new Frame[0];
            return frames;
        }
        n = m.instructions.size();
        insns = m.instructions;
        handlers = new List[n];
        frames = new Frame[n];
        // subroutines = new Subroutine[n];
        queued = new boolean[n];
        queue = new int[n];
        top = 0;
        // computes exception handlers for each instruction
        for (int i = 0; i < m.tryCatchBlocks.size(); ++i) {
            TryCatchBlockNode tcb = (TryCatchBlockNode) m.tryCatchBlocks.get(i);
            int begin = insns.indexOf(tcb.start);
            int end = insns.indexOf(tcb.end);
            for (int j = begin; j < end; ++j) {
                List<TryCatchBlockNode> insnHandlers = handlers[j];
                if (insnHandlers == null) {
                    insnHandlers = new ArrayList<TryCatchBlockNode>();
                    handlers[j] = insnHandlers;
                }
                insnHandlers.add(tcb);
            }
        }
        // computes the subroutine for each instruction:
        /*
         * Subroutine main = new Subroutine(null, m.maxLocals, null); List
         * subroutineCalls = new ArrayList(); Map subroutineHeads = new
         * HashMap(); findSubroutine(0, main, subroutineCalls); while
         * (!subroutineCalls.isEmpty()) { JumpInsnNode jsr = (JumpInsnNode)
         * subroutineCalls.remove(0); Subroutine sub = (Subroutine)
         * subroutineHeads.get(jsr.label); if (sub == null) { sub = new
         * Subroutine(jsr.label, m.maxLocals, jsr);
         * subroutineHeads.put(jsr.label, sub);
         * findSubroutine(insns.indexOf(jsr.label), sub, subroutineCalls); }
         * else { sub.callers.add(jsr); } } for (int i = 0; i < n; ++i) { if
         * (subroutines[i] != null && subroutines[i].start == null) {
         * subroutines[i] = null; } }
         */
        // initializes the data structures for the control flow analysis
        Frame current = newFrame(m.maxLocals, m.maxStack);
        Frame handler = newFrame(m.maxLocals, m.maxStack);
        current.setReturn(interpreter.newValue(Type.getReturnType(m.desc)));
        Type[] args = Type.getArgumentTypes(m.desc);
        int local = 0;
        if ((m.access & ACC_STATIC) == 0) {
            Type ctype = Type.getObjectType(owner);
            current.setLocal(local++, interpreter.newValue(ctype));
        }
        for (int i = 0; i < args.length; ++i) {
            current.setLocal(local++, interpreter.newValue(args[i]));
            if (args[i].getSize() == 2) {
                current.setLocal(local++, interpreter.newValue(null));
            }
        }
        // addition: set stack map top
        stackMapTop = local;
        while (local < m.maxLocals) {
            current.setLocal(local++, interpreter.newValue(null));
        }
        // addition: iterate through all instructions and set frames for all
        // stack map frames (and for neighbour labels if there are any)
        Frame stackmapFrame = current;
        for (int ic = 0; ic < n; ic++) {
            final AbstractInsnNode insn = insns.get(ic);
            if (insn.getType() == AbstractInsnNode.FRAME) {
                stackmapFrame = newFrame((FrameNode) insn, stackmapFrame);
                merge(ic, stackmapFrame);
                int j = 1;
                AbstractInsnNode prevInsn = insn.getPrevious();
                while (prevInsn != null && prevInsn.getOpcode() == -1) {
                    prevInsn = prevInsn.getPrevious();
                    frames[ic - j] = stackmapFrame;
                    j += 1;
                }
            }
        }
        merge(0, current/* , null */);
        // control flow analysis
        while (top > 0) {
            int insn = queue[--top];
            Frame f = frames[insn];
            // Subroutine subroutine = subroutines[insn];
            queued[insn] = false;
            try {
                AbstractInsnNode insnNode = m.instructions.get(insn);
                int insnOpcode = insnNode.getOpcode();
                int insnType = insnNode.getType();
                if (insnType == AbstractInsnNode.LABEL || insnType == AbstractInsnNode.LINE
                    || insnType == AbstractInsnNode.FRAME) {
                    merge(insn + 1, f/* , subroutine */);
                    newControlFlowEdge(insn, insn + 1);
                } else {
                    current.init(f).execute(insnNode, interpreter);
                    // subroutine = subroutine == null ? null :
                    // subroutine.copy();
                    if (insnNode instanceof JumpInsnNode) {
                        JumpInsnNode j = (JumpInsnNode) insnNode;
                        if (insnOpcode != GOTO && insnOpcode != JSR) {
                            merge(insn + 1, current/* , subroutine */);
                            newControlFlowEdge(insn, insn + 1);
                        }
                        int jump = insns.indexOf(j.label);
                        if (insnOpcode == JSR) {
                            // merge(jump, current, new Subroutine(j.label,
                            // m.maxLocals, j));
                            throw new CoroutineGenerationException("<jsr> not allowed");
                        } else {
                            merge(jump, current/* , subroutine */);
                        }
                        newControlFlowEdge(insn, jump);
                    } else if (insnNode instanceof LookupSwitchInsnNode) {
                        LookupSwitchInsnNode lsi = (LookupSwitchInsnNode) insnNode;
                        int jump = insns.indexOf(lsi.dflt);
                        merge(jump, current/* , subroutine */);
                        newControlFlowEdge(insn, jump);
                        for (int j = 0; j < lsi.labels.size(); ++j) {
                            LabelNode label = (LabelNode) lsi.labels.get(j);
                            jump = insns.indexOf(label);
                            merge(jump, current/* , subroutine */);
                            newControlFlowEdge(insn, jump);
                        }
                    } else if (insnNode instanceof TableSwitchInsnNode) {
                        TableSwitchInsnNode tsi = (TableSwitchInsnNode) insnNode;
                        int jump = insns.indexOf(tsi.dflt);
                        merge(jump, current/* , subroutine */);
                        newControlFlowEdge(insn, jump);
                        for (int j = 0; j < tsi.labels.size(); ++j) {
                            LabelNode label = (LabelNode) tsi.labels.get(j);
                            jump = insns.indexOf(label);
                            merge(jump, current/* , subroutine */);
                            newControlFlowEdge(insn, jump);
                        }
                    } else if (insnOpcode == RET) {
                        /*
                         * if (subroutine == null) { throw new
                         * AnalyzerException(
                         * "RET instruction outside of a sub routine"); } for
                         * (int i = 0; i < subroutine.callers.size(); ++i) {
                         * Object caller = subroutine.callers.get(i); int call =
                         * insns.indexOf((AbstractInsnNode) caller); if
                         * (frames[call] != null) { merge(call + 1,
                         * frames[call], current, subroutines[call],
                         * subroutine.access); newControlFlowEdge(insn, call +
                         * 1); } }
                         */
                        throw new CoroutineGenerationException("<ret> not allowed");
                    } else if (insnOpcode != ATHROW && (insnOpcode < IRETURN || insnOpcode > RETURN)) {
                        /*
                         * if (subroutine != null) { if (insnNode instanceof
                         * VarInsnNode) { int var = ((VarInsnNode)
                         * insnNode).var; subroutine.access[var] = true; if
                         * (insnOpcode == LLOAD || insnOpcode == DLOAD ||
                         * insnOpcode == LSTORE || insnOpcode == DSTORE) {
                         * subroutine.access[var + 1] = true; } } else if
                         * (insnNode instanceof IincInsnNode) { int var =
                         * ((IincInsnNode) insnNode).var; subroutine.access[var]
                         * = true; } }
                         */
                        merge(insn + 1, current/* , subroutine */);
                        newControlFlowEdge(insn, insn + 1);
                    }
                }
                List<TryCatchBlockNode> insnHandlers = handlers[insn];
                if (insnHandlers != null) {
                    for (int i = 0; i < insnHandlers.size(); ++i) {
                        TryCatchBlockNode tcb = insnHandlers.get(i);
                        Type type;
                        if (tcb.type == null) {
                            type = Type.getObjectType("java/lang/Throwable");
                        } else {
                            type = Type.getObjectType(tcb.type);
                        }
                        int jump = insns.indexOf(tcb.handler);
                        if (newControlFlowExceptionEdge(insn, jump)) {
                            handler.init(f);
                            handler.clearStack();
                            handler.push(interpreter.newValue(type));
                            merge(jump, handler/* , subroutine */);
                        }
                    }
                }
            } catch (AnalyzerException e) {
                throw new AnalyzerException("Error at instruction " + insn + ": " + e.getMessage(), e);
            } catch (Exception e) {
                throw new AnalyzerException("Error at instruction " + insn + ": " + e.getMessage(), e);
            }
        }
        return frames;
    }

    /*
     * private void findSubroutine(int insn, final Subroutine sub, final List
     * calls) throws AnalyzerException { while (true) { if (insn < 0 || insn >=
     * n) { throw new
     * AnalyzerException("Execution can fall off end of the code"); } if
     * (subroutines[insn] != null) { return; } subroutines[insn] = sub.copy();
     * AbstractInsnNode node = insns.get(insn); // calls findSubroutine
     * recursively on normal successors if (node instanceof JumpInsnNode) { if
     * (node.getOpcode() == JSR) { // do not follow a JSR, it leads to another
     * subroutine! calls.add(node); } else { JumpInsnNode jnode = (JumpInsnNode)
     * node; findSubroutine(insns.indexOf(jnode.label), sub, calls); } } else if
     * (node instanceof TableSwitchInsnNode) { TableSwitchInsnNode tsnode =
     * (TableSwitchInsnNode) node; findSubroutine(insns.indexOf(tsnode.dflt),
     * sub, calls); for (int i = tsnode.labels.size() - 1; i >= 0; --i) {
     * LabelNode l = (LabelNode) tsnode.labels.get(i);
     * findSubroutine(insns.indexOf(l), sub, calls); } } else if (node
     * instanceof LookupSwitchInsnNode) { LookupSwitchInsnNode lsnode =
     * (LookupSwitchInsnNode) node; findSubroutine(insns.indexOf(lsnode.dflt),
     * sub, calls); for (int i = lsnode.labels.size() - 1; i >= 0; --i) {
     * LabelNode l = (LabelNode) lsnode.labels.get(i);
     * findSubroutine(insns.indexOf(l), sub, calls); } } // calls findSubroutine
     * recursively on exception handler successors List insnHandlers =
     * handlers[insn]; if (insnHandlers != null) { for (int i = 0; i <
     * insnHandlers.size(); ++i) { TryCatchBlockNode tcb = (TryCatchBlockNode)
     * insnHandlers.get(i); findSubroutine(insns.indexOf(tcb.handler), sub,
     * calls); } } // if insn does not falls through to the next instruction,
     * return. switch (node.getOpcode()) { case GOTO: case RET: case
     * TABLESWITCH: case LOOKUPSWITCH: case IRETURN: case LRETURN: case FRETURN:
     * case DRETURN: case ARETURN: case RETURN: case ATHROW: return; } insn++; }
     * }
     */
    /**
     * Returns the symbolic stack frame for each instruction of the last
     * recently analyzed method.
     * 
     * @return the symbolic state of the execution stack frame at each bytecode
     *         instruction of the method. The size of the returned array is
     *         equal to the number of instructions (and labels) of the method. A
     *         given frame is <tt>null</tt> if the corresponding instruction
     *         cannot be reached, or if an error occured during the analysis of
     *         the method.
     */
    public Frame[] getFrames() {
        return frames;
    }

    /**
     * Returns the exception handlers for the given instruction.
     * 
     * @param insn
     *            the index of an instruction of the last recently analyzed
     *            method.
     * @return a list of {@link TryCatchBlockNode} objects.
     */
    public List<TryCatchBlockNode> getHandlers(final int insn) {
        return handlers[insn];
    }

    /*
     * private void merge(final int insn, final Frame beforeJSR, final Frame
     * afterRET, final Subroutine subroutineBeforeJSR, final boolean[] access)
     * throws AnalyzerException { Frame oldFrame = frames[insn]; Subroutine
     * oldSubroutine = subroutines[insn]; boolean changes;
     * afterRET.merge(beforeJSR, access); if (oldFrame == null) { frames[insn] =
     * newFrame(afterRET); changes = true; } else { changes =
     * oldFrame.merge(afterRET, access); } if (oldSubroutine != null &&
     * subroutineBeforeJSR != null) { changes |=
     * oldSubroutine.merge(subroutineBeforeJSR); } if (changes && !queued[insn])
     * { queued[insn] = true; queue[top++] = insn; } }
     */
    private void merge(final int insn, final Frame frame /*
                                                          * , final Subroutine
                                                          * subroutine
                                                          */) throws AnalyzerException {
        Frame oldFrame = frames[insn];
        // Subroutine oldSubroutine = subroutines[insn];
        boolean changes;
        if (oldFrame == null) {
            frames[insn] = newFrame(frame);
            changes = true;
        } else {
            changes = oldFrame.merge(frame, interpreter);
        }
        /*
         * if (oldSubroutine == null) { if (subroutine != null) {
         * subroutines[insn] = subroutine.copy(); changes = true; } } else { if
         * (subroutine != null) { changes |= oldSubroutine.merge(subroutine); }
         * }
         */
        if (changes && !queued[insn]) {
            queued[insn] = true;
            queue[top++] = insn;
        }
    }

    /**
     * Creates a control flow graph edge. The default implementation of this
     * method does nothing. It can be overriden in order to construct the
     * control flow graph of a method (this method is called by the
     * {@link #analyze analyze} method during its visit of the method's code).
     * 
     * @param insn
     *            an instruction index.
     * @param successor
     *            index of a successor instruction.
     */
    protected void newControlFlowEdge(final int insn, final int successor) {
    }

    /**
     * Creates a control flow graph edge corresponding to an exception handler.
     * The default implementation of this method does nothing. It can be
     * overriden in order to construct the control flow graph of a method (this
     * method is called by the {@link #analyze analyze} method during its visit
     * of the method's code).
     * 
     * @param insn
     *            an instruction index.
     * @param successor
     *            index of a successor instruction.
     * @return true if this edge must be considered in the data flow analysis
     *         performed by this analyzer, or false otherwise. The default
     *         implementation of this method always returns true.
     */
    protected boolean newControlFlowExceptionEdge(final int insn, final int successor) {
        return true;
    }

    // -------------------------------------------------------------------------
    /**
     * Constructs a new frame that is identical to the given frame.
     * 
     * @param src
     *            a frame.
     * @return the created frame.
     */
    protected Frame newFrame(final Frame src) {
        return new Frame(src);
    }

    private Frame newFrame(FrameNode node, Frame previousFrame) {
        final Frame result = newFrame(previousFrame);
        switch (node.type) {
            case F_APPEND:
                setFrameLocals(result, node.local);
                result.clearStack();
            break;
            case F_CHOP:
                int newStackMapTop = stackMapTop - node.local.size();
                for (int i = newStackMapTop; i < stackMapTop; i++) {
                    result.setLocal(i, interpreter.newValue(null));
                }
                stackMapTop = newStackMapTop;
                result.clearStack();
            break;
            case F_FULL:
                stackMapTop = 0;
                setFrameLocals(result, node.local);
                int maxLocals = result.getLocals();
                for (int i = stackMapTop; i < maxLocals; i++) {
                    result.setLocal(i, interpreter.newValue(null));
                }
                List<Object> stack = node.stack;
                int nStack = stack.size();
                for (int i = 0; i < nStack; i++) {
                    result.push(interpreter.newValue(getTypeFromFrameOpcode(stack.get(i))));
                }
            break;
            case F_SAME:
                result.clearStack();
            break;
            case F_SAME1:
                result.clearStack();
                result.push(interpreter.newValue(getTypeFromFrameOpcode(node.stack.get(0))));
            break;
            default:
                throw new CoroutineGenerationException("Expanded frames not allowed");
        }
        return result;
    }

    /**
     * Constructs a new frame with the given size.
     * 
     * @param nLocals
     *            the maximum number of local variables of the frame.
     * @param nStack
     *            the maximum stack size of the frame.
     * @return the created frame.
     */
    protected Frame newFrame(final int nLocals, final int nStack) {
        return new Frame(nLocals, nStack);
    }

    private void setFrameLocals(final Frame frame, List<Object> locals) {
        int nLocals = locals.size();
        for (int i = 0; i < nLocals; i++) {
            Value newValue = interpreter.newValue(getTypeFromFrameOpcode(locals.get(i)));
            frame.setLocal(stackMapTop, newValue);
            if (newValue.getSize() == 2) {
                frame.setLocal(stackMapTop + 1, interpreter.newValue(null));
                stackMapTop += 2;
            } else {
                stackMapTop += 1;
            }
        }
    }
}
