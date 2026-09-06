// Festival-spike load test (Week 12). Same honest scale-down as Week
// 9's search-load.js and for the same reason - this is a laptop-scale
// dev environment, not production infrastructure. Real numbers at a
// real (smaller) scale, recorded in METRICS.md, never presented as a
// measurement of the full target.
//
// Mix is weighted the way a real Diwali-week traffic spike actually
// looks: mostly browsing/search, a real minority asking the gift
// concierge for an occasion recommendation (the newest, heaviest
// endpoint - real embedding inference + a real pgvector query, not
// comparable in cost to a plain OpenSearch query), and a smaller still
// slice just browsing the catalog outright.
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '1m', target: 20 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    'http_req_duration{name:search}': ['p(95)<200'],
    'http_req_duration{name:catalog}': ['p(95)<200'],
    // Real embedding inference + pgvector retrieval is a genuinely
    // heavier real cost than an indexed OpenSearch query - a looser,
    // still real, threshold rather than the same bar as the other two.
    'http_req_duration{name:concierge}': ['p(95)<1500'],
    http_req_failed: ['rate<0.01'],
  },
};

const searchTerms = ['money plant', 'tulsi', 'snake plant', 'rose', 'lucky bamboo', 'jade plant', 'fern'];
const occasions = ['diwali gift', 'raksha bandhan gift for my brother', 'housewarming gift', 'gift for a new baby'];

export default function () {
  const roll = Math.random();
  if (roll < 0.70) {
    const q = searchTerms[Math.floor(Math.random() * searchTerms.length)];
    const res = http.get(`${__ENV.BASE_URL}/v1/search?q=${encodeURIComponent(q)}&pincode=411001`,
      { tags: { name: 'search' } });
    check(res, { 'search 200': (r) => r.status === 200 });
  } else if (roll < 0.90) {
    const occasion = occasions[Math.floor(Math.random() * occasions.length)];
    const res = http.post(`${__ENV.BASE_URL}/v1/assistant/gift-concierge`,
      JSON.stringify({ query: occasion, destinationPincode: '411001' }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'concierge' } });
    check(res, { 'concierge 200': (r) => r.status === 200 });
  } else {
    const res = http.get(`${__ENV.BASE_URL}/v1/catalog`, { tags: { name: 'catalog' } });
    check(res, { 'catalog 200': (r) => r.status === 200 });
  }
}
