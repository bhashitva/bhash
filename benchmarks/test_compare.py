# SPDX-License-Identifier: Apache-2.0
import unittest
from compare import fmt, render


class ComparisonTest(unittest.TestCase):
    def test_short_profile_cannot_be_presented_as_baseline(self):
        data = {"configuration": {"trials":1,"compile_trials":1,"warmup_ms":100,"batch_ms":50,"batches":1},
                "environment": {"platform":"test","cpu":"test","logical_cpus":1}, "compiler_sha256":"test", "results":[]}
        for target in ('jvm','clr','native'):
            data['results'].append({"name":"startup","size":"minimal","target":target,"runtime":[{}],"compile":[{}],"application_bytes":1024,
                                    "summary": {k:{"median":1} for k in ('wall_s','cpu_s','peak_rss_bytes','compile_wall_s')}})
        report = render(data)
        self.assertIn("Conformance/short run only",report)
        self.assertIn("Validated cases: 3; runtime trials: 3; compile trials: 3",report)
        self.assertEqual(fmt(None),'—')


if __name__ == '__main__': unittest.main()
