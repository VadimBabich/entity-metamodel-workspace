// A load smoke against the running stack: a few callers list a folder for a short while. It fails when requests fail
// or answer anything but 200; response times are reported, never judged, because a shared CI runner gives no stable
// baseline.
import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.BASE_URL;
const token = __ENV.TOKEN;

export const options = {
  vus: 5,
  duration: '20s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const response = http.get(`${baseUrl}/api/v1/top-level-folders`, {
    headers: { Authorization: `Bearer ${token}` },
  });

  check(response, { 'the folder listing answers 200': (listing) => listing.status === 200 });
}
