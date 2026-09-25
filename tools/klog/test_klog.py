"""python3 tools/klog/test_klog.py"""

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import klog  # noqa: E402


def line(ts, message, level="INFO", session="s1", activity=None, **fields):
    record = {"timestampUtc": f"2026-09-24T{ts}.000Z", "level": level, "message": message,
              "sessionId": session, "fields": fields}
    if activity is not None:
        record["activityId"] = activity
    return json.dumps(record)


def write_zip(path, session, entries):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("diagnostics.json", json.dumps({"sessionId": session}))
        for name, lines in entries.items():
            archive.writestr(name, "\n".join(lines) + "\n")


class PoolTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def pool(self, *names):
        return klog.load_pool([str(self.dir / name) for name in names])

    def test_split_streams_merge_without_duplicates(self):
        header = line("10:00:00", klog.HEADER, gitSha="abc", gitDirty="false")
        process = line("10:00:01", klog.PROCESS_STARTED)
        started = line("10:01:00", klog.STARTED, activity=7)
        sample = line("10:01:05", "Location sample received", "DEBUG", activity=7)
        stopped = line("10:05:00", klog.STOPPED, activity=7)
        write_zip(self.dir / "korno-diagnostics-s1.zip", "s1",
                  {"logs/korno-s1-0.jsonl": [header, process, started, stopped]})
        write_zip(self.dir / "korno-activity-7-s1.zip", "s1", {
            "logs/activity-7.jsonl": [started, sample, stopped, TRUNCATION_LINE],
            "context/app.jsonl": [header, process],
        })

        pool = self.pool("korno-diagnostics-s1.zip", "korno-activity-7-s1.zip")

        self.assertEqual(len(pool.records), 6)  # header, process, started, sample, stopped, маркер
        app, activity = pool.inputs
        self.assertEqual(app.unique, 0)
        self.assertEqual(app.covered_by, {1})
        self.assertEqual(activity.unique, 2)  # sample и маркер обрезки
        [recorded] = pool.activities
        self.assertEqual(recorded.completeness, "full")
        self.assertIsNotNone(recorded.first(klog.TRUNCATED))
        self.assertEqual(pool.sessions["s1"].build, "abc")

    def test_truncation_markers_of_different_files_stay_separate(self):
        for activity in (1, 2):
            write_zip(self.dir / f"korno-activity-{activity}-s1.zip", "s1", {
                f"logs/activity-{activity}.jsonl": [
                    line(f"1{activity}:00:00", klog.STARTED, activity=activity), TRUNCATION_LINE],
            })
        pool = self.pool("korno-activity-1-s1.zip", "korno-activity-2-s1.zip")
        self.assertEqual([a.first(klog.TRUNCATED) is not None for a in pool.activities], [True, True])

    def test_reused_activity_id_is_two_activities(self):
        write_zip(self.dir / "korno-diagnostics-s2.zip", "s2", {"logs/korno-s2-0.jsonl": [
            line("10:00:00", klog.STARTED, session="s1", activity=3),
            line("10:00:05", "Location sample received", "DEBUG", session="s1", activity=3),
            line("11:00:00", klog.STARTED, session="s2", activity=3),
        ]})
        first, second = self.pool("korno-diagnostics-s2.zip").activities
        self.assertEqual(first.completeness, "full(app)")  # старый общий поток с сэмплами
        self.assertEqual(second.completeness, "skeleton")
        self.assertEqual((first.sessions, second.sessions), (["s1"], ["s2"]))

    def test_export_session_comes_from_diagnostics_not_name(self):
        write_zip(self.dir / "korno-diagnostics-old.zip", "new",
                  {"logs/korno-new-0.jsonl": [line("10:00:00", klog.PROCESS_STARTED, session="new")]})
        [source] = self.pool("korno-diagnostics-old.zip").inputs
        self.assertEqual((source.name_session, source.export_session), ("old", "new"))


class CommandsTest(unittest.TestCase):
    def records(self, *lines):
        return [klog.Record(json.loads(text), klog.parse_time(json.loads(text)["timestampUtc"]), 1)
                for text in lines]

    def test_timeline_collapses_runs_by_projection_key(self):
        rows = klog.timeline_rows(self.records(
            line("10:00:00", "Activity recognized", detectedActivity="WALKING", confidencePercent="80"),
            line("10:00:05", "Activity recognized", detectedActivity="WALKING", confidencePercent="90"),
            line("10:00:10", "Activity recognized", detectedActivity="STILL", confidencePercent="100"),
        ), full=False)
        self.assertEqual([row.count for row in rows], [2, 1])
        self.assertEqual(klog.summarize("confidencePercent", rows[0].values["confidencePercent"]), "80–90")

    def test_jq_fields_are_typed(self):
        [record] = self.records(line("10:00:00", "x", reportedSpeed="null", isStop="true",
                                     pointCount="3", accuracyMeters="1.5", gitSha="1234567"))
        fields = klog.normalized(record, origin=record.t - 90)
        self.assertEqual(fields["fields"], {"reportedSpeed": None, "isStop": True, "pointCount": 3,
                                            "accuracyMeters": 1.5, "gitSha": "1234567"})
        self.assertEqual(fields["at"], "1:30")

    def test_samples_join_by_timestamp_and_order(self):
        activity = klog.Activity(1, self.records(
            line("10:00:00", "Location sample received", sampleTimestamp="100", status="RECORDING"),
            line("10:00:00", "Location sample coordinates", "VERBOSE", sampleTimestamp="100", latitude="1"),
            line("10:00:00", "Track point processed", "DEBUG", isStop="true"),
            line("10:00:01", "Location sample rejected before processing", sampleTimestamp="100",
                 rejectedBecause="DUPLICATE"),
            line("10:00:01", "Automatic recording state changed", sampleTimestamp="100", to="AUTO_PAUSE"),
            line("10:00:02", "Location sample received", sampleTimestamp="200"),
            line("10:00:02", "Track point processed", "DEBUG", isStop="false"),
        ))
        first, duplicate, second = klog.sample_rows(activity)
        self.assertEqual((first["coordinates"]["latitude"], first["processed"]["isStop"], first["decision"]),
                         ("1", "true", "→AUTO_PAUSE"))
        self.assertEqual((duplicate["rejected"], "processed" in duplicate), ("DUPLICATE", False))
        self.assertEqual(second["processed"]["isStop"], "false")

    def test_offsets(self):
        self.assertEqual([klog.parse_offset(t) for t in ("6m30s", "6:30", "390", "-2m")], [390, 390, 390, -120])


TRUNCATION_LINE =json.dumps({"level": "WARN", "category": "APP", "tag": "LogFileStore",
                              "message": klog.TRUNCATED})

if __name__ == "__main__":
    unittest.main()
