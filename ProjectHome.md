Coroutines are (quoting [wikipedia](http://en.wikipedia.org/wiki/Coroutines)):

> program components that generalize subroutines to allow multiple entry points for suspending and resuming execution at certain locations.

While in many modern languages coroutines are built-in (Python being the most notable example) or similar, often simplified, constructs are provided (like C#'s yield), Java lacks any coroutine support - even in this simplified form. I found this situation uncomfortable, because many times I had been struck with feeling that any coroutine support would have greatly helped with a problem at hand.
After reading inspirational article about coroutine support in C, which you can find [here](http://www.chiark.greenend.org.uk/~sgtatham/coroutines.html) and inspecting potential JVM constraints, it became clear that coroutines could be implemented on bytecode level using [instrumentation](http://java.sun.com/javase/6/docs/api/java/lang/instrument/package-summary.html) to rearrange user code. And so the project was started...
This library has been created to help anyone who, as myself, deeply envied Python programmers their generators and coroutines :-)

Full documentation is in wiki content under Wiki tab.

This implementation should be considered experimental at this point. While tested as thoroughly as it could be, I suspect that somewhere some code exists that fails to be instrumented properly. Yet, with the most welcome help of the user community, this experimental status will be removed as soon as confidence in this implementation grows to sufficient level.

If you want to use this project through Maven check out [1\_2\_version](1_2_version.md) page for all the details.

1.2 version released on 9th of Novemeber brought a few changes to configuration, project switched to Maven and had been repackaged - please read [1\_2\_version](1_2_version.md) if you plan to switch from a previous version. Thanks


[![](http://coroutines.googlecode.com/svn/trunk/poweredbyasm.gif)](http://asm.objectweb.org)