"""Portable deployment inputs and actual authenticated backup stream round trips."""

import copy
import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import eks_handoff as handoff


def resource(kind, name, namespace="govbiz-msa", **fields):
    return {
        "apiVersion": "v1",
        "kind": kind,
        "metadata": {
            "name": name,
            "namespace": namespace,
            "uid": "original",
            "resourceVersion": "12",
            "annotations": {
                "kubectl.kubernetes.io/last-applied-configuration": "PRIVATE"
            },
        },
        **fields,
    }


def configuration():
    claim = resource(
        "PersistentVolumeClaim",
        "data-core-mysql-0",
        spec={
            "volumeName": "source-only",
            "resources": {"requests": {"storage": "1Gi"}},
        },
    )
    store = resource(
        "StatefulSet",
        "core-mysql",
        spec={
            "replicas": 1,
            "serviceName": "core-mysql",
            "volumeClaimTemplates": [{"metadata": {"name": "data"}}],
            "template": {
                "spec": {
                    "nodeName": "source-control-plane",
                    "containers": [
                        {
                            "name": "mysql",
                            "image": "mysql:8.4",
                            "imagePullPolicy": "Never",
                        }
                    ],
                }
            },
        },
    )
    pod = resource(
        "Pod",
        "core-mysql-0",
        spec={
            "volumes": [
                {"persistentVolumeClaim": {"claimName": claim["metadata"]["name"]}}
            ]
        },
        status={
            "containerStatuses": [
                {
                    "name": "mysql",
                    "imageID": "docker-pullable://mysql@sha256:" + "a" * 64,
                }
            ]
        },
    )
    app = resource(
        "Application",
        "core-service",
        "argocd",
        spec={
            "project": "govbiz-fork",
            "destination": {
                "namespace": "govbiz-msa",
                "server": "https://source.invalid",
            },
            "source": {
                "targetRevision": "b" * 40,
                "helm": {
                    "kubeVersion": "1.36.4",
                    "valuesObject": {
                        "serviceName": "core-service",
                        "env": {"ACCOUNT_DEV_LOGIN_ENABLED": "true"},
                    },
                },
            },
        },
    )
    return {
        "claims": [["govbiz-msa", "data-core-mysql-0"]],
        "resources": [claim, store, pod],
        "applications": [app],
        "projects": [],
    }


class HandoffTests(unittest.TestCase):
    @unittest.skipUnless(
        os.name == "posix", "Filesystem ownership/link restore runs in Linux CI"
    )
    def test_compressed_tar_restores_contents_permissions_and_links(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, target = root / "source", root / "target"
            source.mkdir()
            target.mkdir()
            original = source / "한글.txt"
            original.write_text("보존할 데이터", encoding="utf-8")
            original.chmod(0o640)
            os.link(original, source / "hardlink")
            (source / "symlink").symlink_to("한글.txt")
            key = b"4" * 64 + b"\n"
            archive = root / "archive"
            manifest = handoff.archive_stream(
                [
                    "tar",
                    "--numeric-owner",
                    "--sparse",
                    "-czpf",
                    "-",
                    "-C",
                    str(source),
                    ".",
                ],
                archive,
                key,
            )
            handoff.restore_stream(
                [
                    "tar",
                    "--numeric-owner",
                    "--same-owner",
                    "--same-permissions",
                    "-xzpf",
                    "-",
                    "-C",
                    str(target),
                ],
                archive,
                manifest,
                key,
            )
            restored = target / "한글.txt"
            self.assertEqual(restored.read_bytes(), original.read_bytes())
            self.assertEqual(restored.stat().st_mode, original.stat().st_mode)
            self.assertEqual(restored.stat().st_uid, original.stat().st_uid)
            self.assertEqual(restored.stat().st_gid, original.stat().st_gid)
            self.assertEqual(
                restored.stat().st_ino, (target / "hardlink").stat().st_ino
            )
            self.assertEqual(os.readlink(target / "symlink"), "한글.txt")

    def test_destination_preserves_db_identity_but_removes_source_storage_and_credentials(
        self,
    ):
        config = configuration()
        original = copy.deepcopy(config)
        out = handoff.destination(
            config, "ip-10-0-0-1", "registry.example/elasticsearch@sha256:" + "c" * 64
        )
        self.assertEqual(config, original)
        claim = out["10-claims.json"]["items"][0]
        self.assertEqual(
            claim["metadata"], {"name": "data-core-mysql-0", "namespace": "govbiz-msa"}
        )
        self.assertNotIn("volumeName", claim["spec"])
        self.assertEqual(claim["spec"]["storageClassName"], "govbiz-ebs-gp3")
        self.assertEqual(claim["spec"]["resources"]["requests"]["storage"], "10Gi")
        store = out["30-data-stopped.json"]["items"][0]["spec"]
        self.assertEqual(store["replicas"], 0)
        self.assertNotIn("volumeClaimTemplates", store)
        pod = store["template"]["spec"]
        self.assertNotIn("nodeName", pod)
        self.assertEqual(
            pod["volumes"][0]["persistentVolumeClaim"]["claimName"], "data-core-mysql-0"
        )
        self.assertIn("@sha256:", pod["containers"][0]["image"])
        app = out["50-applications.json"]["items"][0]
        self.assertEqual(app["spec"]["source"]["targetRevision"], "b" * 40)
        self.assertFalse(app["spec"]["syncPolicy"]["automated"]["enabled"])
        self.assertNotIn("kubeVersion", app["spec"]["source"]["helm"])
        self.assertEqual(
            app["spec"]["source"]["helm"]["valuesObject"]["env"][
                "ACCOUNT_DEV_LOGIN_ENABLED"
            ],
            "false",
        )
        self.assertNotIn("PRIVATE", json.dumps(out))

    def test_skip_unreferenced_old_claims_keep_receipts(self):
        config = configuration()
        config["resources"].append(resource("PersistentVolumeClaim", "old-stage2"))
        self.assertEqual(
            handoff.referenced_claims(config["resources"]),
            [
                ("govbiz-msa", "catalog-sync-receipts"),
                ("govbiz-msa", "data-core-mysql-0"),
            ],
        )

    def test_backup_helper_cannot_write_source_and_has_no_api_token(self):
        pod = handoff.helper(
            "govbiz-msa", ["data-core-mysql-0"], "source", "copy", readonly=True
        )["spec"]
        self.assertFalse(pod["automountServiceAccountToken"])
        self.assertTrue(pod["volumes"][0]["persistentVolumeClaim"]["readOnly"])
        self.assertTrue(pod["containers"][0]["volumeMounts"][0]["readOnly"])

    def test_same_cluster_restore_and_live_backup_are_rejected(self):
        config = {"namespaces": {n: "original" for n in handoff.NAMESPACES}}
        with (
            patch.object(
                handoff, "get", return_value={"metadata": {"uid": "original"}}
            ),
            self.assertRaisesRegex(handoff.snapshot.SnapshotError, "original cluster"),
        ):
            handoff.stopped([], config, source=False)
        with (
            patch.object(
                handoff,
                "get",
                side_effect=[
                    {"metadata": {"uid": "original"}},
                    {"items": [{"spec": {"replicas": 1}}]},
                ],
            ),
            self.assertRaisesRegex(handoff.snapshot.SnapshotError, "Stop application"),
        ):
            handoff.stopped([], config, source=True)

    def test_encrypted_multichunk_round_trip_and_corruption_before_write(self):
        key = b"4" * 64 + b"\n"
        payload = "한글 계정/공고/평가\n".encode() * 100
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source"
            source.write_bytes(payload)
            archive = root / "archive"
            reader = [
                sys.executable,
                "-c",
                "import pathlib,sys; sys.stdout.buffer.write(pathlib.Path(sys.argv[1]).read_bytes())",
                str(source),
            ]
            with patch.object(handoff, "CHUNK_SIZE", 1024):
                manifest = handoff.archive_stream(reader, archive, key)
            self.assertGreater(len(manifest["chunks"]), 1)
            self.assertEqual(manifest["sha256"], hashlib.sha256(payload).hexdigest())
            target = root / "target"
            writer = [
                sys.executable,
                "-c",
                "import pathlib,sys; pathlib.Path(sys.argv[1]).write_bytes(sys.stdin.buffer.read())",
                str(target),
            ]
            handoff.restore_stream(writer, archive, manifest, key)
            self.assertEqual(target.read_bytes(), payload)
            target.unlink()
            chunk = archive / manifest["chunks"][-1]["file"]
            chunk.write_bytes(chunk.read_bytes() + b"tampered")
            with self.assertRaisesRegex(handoff.snapshot.SnapshotError, "Damaged"):
                handoff.restore_stream(writer, archive, manifest, key)
            self.assertFalse(target.exists())

    def test_failed_tar_never_returns_a_complete_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            command = [
                sys.executable,
                "-c",
                "import sys; sys.stdout.buffer.write(b'partial'); sys.stderr.write('context deadline exceeded'); sys.exit(1)",
            ]
            with self.assertRaisesRegex(
                handoff.snapshot.SnapshotError, "deadline exceeded"
            ):
                handoff.archive_stream(
                    command, Path(temp) / "archive", b"4" * 64 + b"\n"
                )


if __name__ == "__main__":
    unittest.main()
