.PHONY: test
repl:
	clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "0.8.3"} cider/cider-nrepl {:mvn/version "0.25.7"} refactor-nrepl/refactor-nrepl {:mvn/version "2.5.1"}}}'  -m nrepl.cmdline --middleware "[cider.nrepl/cider-middleware refactor-nrepl.middleware/wrap-refactor]" --interactive
test:
	clj -A:test:runner

deploy: test
	clj -Spom
	mvn deploy

push:
	mvn deploy
