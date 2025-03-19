CC = gcc
CFLAGS = -fPIC -I../include -Wall

# Flags for building the shared library
LIB_LDFLAGS = -shared -nostartfiles -L../lib -ldirtree -ldl
  
# Flags for building the server executable
SERVER_LDFLAGS = -L../lib -ldirtree -ldl

all: mylib.so server

# Build the interposition library
mylib.so: mylib.c
	$(CC) $(CFLAGS) -o $@ $< $(LIB_LDFLAGS)

# Build the server executable
server: server.c
	$(CC) $(CFLAGS) -o $@ $< $(SERVER_LDFLAGS)

clean:
	rm -f mylib.so server

.PHONY: all clean
