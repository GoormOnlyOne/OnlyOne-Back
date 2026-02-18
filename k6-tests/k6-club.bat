@echo off
if not exist k6-results mkdir k6-results
docker run --rm --network onlyone-back_onlyone-network -v "%cd%":/k6 -e BASE_URL=http://host.docker.internal:8080 -e JWT_SECRET=7e9eeb12d176a2d72f554c6b096522b4e1a34d799727e45a96f192bbff2a2a851ede29ed24b10b6e6b1835ac94380e2469df99ff9713477bf4d43eeaa9cd16a3 grafana/k6 run --out json=/k6/k6-results/club-load-results.json /k6/k6-tests/club-load-test.js
