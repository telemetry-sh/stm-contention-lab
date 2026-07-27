CLOJURE ?= clojure
NODE ?= node
HOST ?= 127.0.0.1
PORT ?= 8080
IMAGE ?= stm-contention-lab:test

.PHONY: prepare lint test check run json clean docker-check

prepare:
	$(CLOJURE) -P

lint:
	$(CLOJURE) -M -e "(require 'stm-lab.json 'stm-lab.model 'stm-lab.server 'stm-lab.cli)"
	$(NODE) --check public/app.js
	sh -n tests/server_test.sh
	sh -n tests/container_test.sh

test:
	$(CLOJURE) -M:test
	sh tests/server_test.sh $(CLOJURE)
	$(CLOJURE) -M:json >/dev/null

check: lint test

run:
	HOST=$(HOST) PORT=$(PORT) $(CLOJURE) -M:run

json:
	$(CLOJURE) -M:json

clean:
	rm -rf .cpcache target

docker-check:
	docker build --tag $(IMAGE) .
	test "$$(docker inspect --format '{{.Config.User}}' $(IMAGE))" = "10001:10001"
	sh tests/container_test.sh $(IMAGE)
