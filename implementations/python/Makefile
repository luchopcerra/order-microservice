.PHONY: build test lint fmt

build:
	python -m py_compile app/main.py

test:
	python -m unittest discover -s tests

lint:
	python -m py_compile app/main.py

fmt:
	python -m compileall app
