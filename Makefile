SRC = src/TCPend.java
CLS = src/TCPend.class

all: $(CLS)

$(CLS): $(SRC)
	javac $(SRC)

clean:
	rm -f src/*.class
