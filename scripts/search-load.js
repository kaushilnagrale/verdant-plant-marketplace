import http from 'k6/http';
import { check } from 'k6';

export const options = {
  stages: [
    { duration: '1m', target: 100 },
    { duration: '2m', target: 500 },
    { duration: '1m', target: 100 },
  ],
  thresholds: { http_req_duration: ['p(95)<150', 'p(99)<200'] },
};

const terms = ['money plant', 'tulsi', 'snake plant', 'pothos', 'fern', 'bamboo', 'indoor plant'];

export default function () {
  const q = terms[Math.floor(Math.random() * terms.length)];
  const res = http.get(`${__ENV.BASE_URL}/v1/search?q=${encodeURIComponent(q)}&pincode=411001`);
  check(res, { 'status 200': (r) => r.status === 200 });
}
