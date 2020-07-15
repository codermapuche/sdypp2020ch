docker stop $(docker ps -aq) && docker rm $(docker ps -aq)
docker build --tag front-static:latest ./docker-images/frontend-static
docker build --tag front-balancer:latest ./docker-images/frontend-balancer
docker build --tag back-rest:latest -f ./docker-images/backend-rest/Dockerfile ./api
docker build --tag back-balancer:latest ./docker-images/backend-balancer
docker build --tag miner-rest:latest -f ./docker-images/miner-rest/Dockerfile ./api
docker build --tag miner-balancer:latest ./docker-images/miner-balancer
docker build --tag storage-db:latest ./docker-images/storage-db
docker build --tag storage-balancer:latest ./docker-images/storage-balancer
docker build --tag mq-balancer:latest ./docker-images/mq-balancer
docker build --tag mq-rabbit:latest ./docker-images/mq-rabbit