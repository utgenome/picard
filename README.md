Picard 
===

This project is a clone of Picard library for reading SAM/BAM files <http://picard.sourceforge.net/>. The original Picard is not available from Maven's central repository, so to deploy of Picard's jars to the Maven central, we rewrote the build script in sbt, a build tool for Scala/Java. 

## Usage

Add the folloing settings to your project files:
### Maven 
        <dependency>
            <groupId>org.utgenome.thirdparty</groupId>
            <artifactId>picard</artifactId>
            <version>1.86p</version>
        </dependency>

### Scala (sbt)

    libraryDependencies += "org.utgenome.thirdparty" % "picard" % "1.86p"

## Development notes

### Sync with the original SVN repository

Install [svn2git](https://github.com/nirvdrum/svn2git), then do:

	svn2git --rebase




