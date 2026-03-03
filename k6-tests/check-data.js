import { generateJWT, headers, BASE_URL } from './lib/common.js';
import http from 'k6/http';

export const options = { iterations: 1, vus: 1 };

export default function () {
    const token = generateJWT({ userId: 1, kakaoId: 1000001, status: 'ACTIVE', role: 'ROLE_USER' });
    const hdrs = headers(token);

    // Filter-only search (MySQL path) - no keyword
    const res1 = http.get(`${BASE_URL}/api/v1/search/interests?interestId=2&page=0`, { headers: hdrs });
    console.log(`[Interest=2 운동] status=${res1.status}`);
    try {
        const body = JSON.parse(res1.body);
        if (body.data && body.data.length > 0) {
            body.data.slice(0, 3).forEach((c, i) => {
                console.log(`  ${i}: name="${c.name}", city/district="${c.district}", interest="${c.interest}"`);
            });
            console.log(`  total: ${body.data.length} results`);
        } else {
            console.log('  No results');
        }
    } catch (e) { console.log('  Parse error: ' + e); }

    // Location search (MySQL path)
    const res2 = http.get(`${BASE_URL}/api/v1/search/locations?city=${encodeURIComponent('서울')}&district=${encodeURIComponent('강남구')}&page=0`, { headers: hdrs });
    console.log(`\n[Location 서울/강남구] status=${res2.status}`);
    try {
        const body = JSON.parse(res2.body);
        if (body.data && body.data.length > 0) {
            body.data.slice(0, 3).forEach((c, i) => {
                console.log(`  ${i}: name="${c.name}", interest="${c.interest}"`);
            });
            console.log(`  total: ${body.data.length} results`);
        } else {
            console.log('  No results');
        }
    } catch (e) { console.log('  Parse error: ' + e); }

    // Keyword search (ES path)
    const res3 = http.get(`${BASE_URL}/api/v1/search?keyword=${encodeURIComponent('축구')}`, { headers: hdrs });
    console.log(`\n[Keyword 축구] status=${res3.status}`);
    try {
        const body = JSON.parse(res3.body);
        if (body.data && body.data.length > 0) {
            body.data.slice(0, 3).forEach((c, i) => {
                console.log(`  ${i}: name="${c.name}", district="${c.district}"`);
            });
            console.log(`  total: ${body.data.length} results`);
        } else {
            console.log('  No results or 0');
        }
    } catch (e) { console.log('  Parse error: ' + e); }
}
