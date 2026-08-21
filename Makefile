.PHONY: test test-clr test-all

# JVM
test:
	clojure -M:test:runner

# ClojureCLR
test-clr:
	cljr -X:test

test-all: test test-clr

deploy: test
	clj -Spom
	mvn deploy

push:
	mvn deploy
