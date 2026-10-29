#!/usr/bin/env python3
"""Offline regression checks for public issue workflow routing."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
DRAIN = yaml.safe_load((ROOT / '.github/workflows/public-issue-drain.yml').read_text())
AGENT = yaml.safe_load((ROOT / '.github/workflows/public-issue.yml').read_text())


class WorkflowTest(unittest.TestCase):
    def test_queue_uses_current_labels(self):
        step = DRAIN['jobs']['queue']['steps'][0]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'gh').write_text('''#!/bin/bash
if [[ "$1 $2" == "issue view" ]]; then
  cat "$STATE"
elif [[ "$1 $2" == "issue edit" ]]; then
  echo '["bug", "agent-backlog"]' > "$STATE"
  echo edit >> "$LOG"
else
  exit 1
fi
''')
            (path / 'gh').chmod(0o755)
            (path / 'state').write_text('["bug"]')
            env = dict(os.environ, PATH=directory + ':' + os.environ['PATH'],
                       STATE=str(path / 'state'), LOG=str(path / 'log'),
                       ENABLED='true', BODY='x' * 40, ISSUE='19', REPOSITORY='demo/repo')
            for _ in range(2):
                subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', step['run']], env=env, check=True)
            self.assertEqual((path / 'log').read_text().splitlines(), ['edit'])
        self.assertIn('github.event.issue.number', DRAIN['jobs']['queue']['concurrency']['group'])

    def test_gate_outputs_and_status_paths(self):
        gate = AGENT['jobs']['intake']['steps'][0]
        self.assertEqual(gate['env']['ENABLED'], '${{ vars.PUBLIC_ISSUE_AGENT_ENABLED }}')
        self.assertIn('run=stop', gate['run'])
        self.assertIn('run=go', gate['run'])
        self.assertIn('over-budget=go', gate['run'])
        self.assertIn("outputs.run == 'go'", AGENT['jobs']['repair']['if'])
        self.assertIn("outputs.over-budget != 'go'", AGENT['jobs']['intake-declined']['if'])
        for job in ('intake-declined', 'publish', 'declined', 'failed'):
            scripts = '\n'.join(step.get('run', '') for step in AGENT['jobs'][job]['steps'])
            self.assertIn('<!-- cammelli-bot-status -->', scripts)
            self.assertIn('github-actions[bot]', scripts)
            self.assertIn('--method PATCH', scripts)
            self.assertIn('--method POST', scripts)
        self.assertNotIn('comment', DRAIN['jobs']['queue']['steps'][0]['run'])


if __name__ == '__main__':
    unittest.main()
