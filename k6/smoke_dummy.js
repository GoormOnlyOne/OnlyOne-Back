import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const ticks = new Counter('dummy_ticks');
const tickLatency = new Trend('dummy_latency');

export const options = { vus: 2, duration: '10s' };

export default function () {
    const start = Date.now();
    sleep(0.2);           // 아무 요청 없이 쉬기
    ticks.add(1);
    tickLatency.add(Date.now() - start);
}
