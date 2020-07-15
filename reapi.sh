docker-compose stop back.rest.1
docker-compose stop back.rest.2
docker-compose stop miner.rest.1
docker-compose stop miner.rest.2

docker-compose up -d --build back.rest.1
docker-compose up -d --build back.rest.2
docker-compose up -d --build miner.rest.1
docker-compose up -d --build miner.rest.2