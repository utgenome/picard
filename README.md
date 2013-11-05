Picard 
===

This project is a clone of Picard library <http://picard.sourceforge.net/>, a useful tool for managing SAM/BAM format data in Java/Scala. Unfortunately, however, Picard has not been available from Maven's central repository. In order to use Picard with Maven and sbt projects, we created a new build script for Picard, and now the Picard library becomes available in Maven and sbt projects.

## Usage
To use picard, add the folloing settings to your project files:
### Maven 
        <dependency>
            <groupId>org.utgenome.thirdparty</groupId>
            <artifactId>picard</artifactId>
            <version>1.86.0</version>
        </dependency>


### Scala (sbt)

    libraryDependencies += "org.utgenome.thirdparty" % "picard" % "1.86.0"

The version number has a suffix like ".0" followed by the original Picard version.

## Install Picard command-line tools

	$ git clone git://github.com/utgenome/picard.git
	$ cd picard
	$ bin/sbt pack
	$ cd target/pack
	$ make install

Picard comamnd-line tools will be installed to `$(HOME)/local/bin`. Configure your `PATH` environment vaiable in your shell profile (e.g., .bash_profile, .zprofile, etc.):

       export PATH = $HOME/local/bin:$PATH

## Development notes

### Sync with the original SVN repository
=======
This is an unofficial clone of picard library for reading SAM/BAM fiels. <http://picard.sourceforge.net/>

This project uses sbt to build Picard to enable deployiment of Picard's jars to the Maven central repository.


## Sync with the original SVN repository

Install [svn2git](https://github.com/nirvdrum/svn2git), then do:

	svn2git --rebase




