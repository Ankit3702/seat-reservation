burst:
	./burst.sh $(URL)

build:
	docker build -t seat-reservation .

run:
	docker compose up --build
