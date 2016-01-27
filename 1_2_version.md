1.2 version was major rewrite and the project switched to Maven. Internally this version makes use of the asm's tree package. It has made the code cleaner and more robust. Public interface did not change though (except one java agent option is now removed and one is not used, see appropriate sections of UserGuide for details). **IMPORTANT!!!** whole project has been repackaged to `pl.clareo.coroutines` package. So please update all your imports after you update. Most notably there were changes in names of loggers and properties (now start with pl.clareo.coroutines). UserGuide has been updated so, once again, please refer to it if in doubt. Apart from these there were minor additions to `CoIterator` interface and minor improvements in how to pass to the java agent which classes to inspect for presence of coroutines (you can specify now a whole package as well as a single class name etc.).

# Maven #

This project uses now maven as its primary build model. Artifacts are currently hosted in the CLAREO repository. You can configure maven to use this repository to automate your build process

```
<distributionManagement>
    <repository>
        <id>maven.clareo.pl</id>
        <name>maven.clareo.pl-releases</name>
        <url>http://maven.clareo.pl/artifactory/clareo</url>
    </repository>
    <snapshotRepository>
        <id>maven.clareo.pl</id>
        <name>maven.clareo.pl-snapshots</name>
        <url>http://maven.clareo.pl/artifactory/clareo</url>
    </snapshotRepository>
</distributionManagement>
```

I will try to sync to a public repository soon.