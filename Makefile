JAVAC = javac
JAVAC_FLAGS = -cp .:sqlite-jdbc-3.43.0.0.jar
HOSTNAME=$(shell hostname)

# Java interpreter
JAVA = java

# Main class
MAIN_CLASS = URLShortner
PROXY_CLASS = ProxyServer
PARALLEL_PUT = performanceTesting/parallelPutAll
PARALLEL_GET = performanceTesting/parallelGetAll
PARALLEL_PUT2 = performanceTesting/parallelPutAll2
PARALLEL_GET2 = performanceTesting/parallelGetAll2

all: compile compileproxy compileTest

compile:
	$(JAVAC) -g $(JAVAC_FLAGS) $(MAIN_CLASS).java

compileproxy:
	$(JAVAC) -g $(PROXY_CLASS).java

compileTest:
	$(JAVAC) -g $(PARALLEL_GET).java
	$(JAVAC) -g $(PARALLEL_PUT).java
	$(JAVAC) -g $(PARALLEL_GET2).java
	$(JAVAC) -g $(PARALLEL_PUT2).java

run:
	$(JAVA) $(JAVAC_FLAGS) $(MAIN_CLASS) $(HOSTNAME)

debug:
	jdb -classpath .:sqlite-jdbc-3.43.0.0.jar $(MAIN_CLASS)

proxy:
	$(JAVA) $(PROXY_CLASS)

clean:
	rm -f *.class

.PHONY: all compile run clean