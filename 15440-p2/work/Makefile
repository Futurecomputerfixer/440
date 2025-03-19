CLASSPATH = $(PWD):$(PWD)/../lib

JAVAC = javac -cp $(CLASSPATH)

SOURCES = Proxy.java Server.java
CLASSES = $(SOURCES:.java=.class)

all: $(CLASSES)

%.class: %.java
	$(JAVAC) $<

clean:
	rm -f *.class
