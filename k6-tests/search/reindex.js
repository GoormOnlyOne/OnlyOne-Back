import { generateJWT, headers, BASE_URL } from '../lib/common.js';
import http from 'k6/http';

export const options = { iterations: 1, vus: 1 };

export default function () {
    const token = generateJWT({ userId: 1, kakaoId: 1000001, status: 'ACTIVE', role: 'ROLE_USER' });
    const res = http.post(`${BASE_URL}/api/v1/admin/search/reindex`, null, { headers: headers(token) });
    console.log(`Reindex response: ${res.status} - ${res.body}`);
}
