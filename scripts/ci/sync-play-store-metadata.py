#!/usr/bin/env python3
"""
Syncs store listing metadata (titles, short/full descriptions, icon, feature graphic, screenshots)
from distribution/metadata/android/<language>/ to the Google Play Developer API (androidpublisher v3).
"""

import argparse
import hashlib
import os
import sys
import json
import glob

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="Upload all metadata and commit even if unchanged")
    args = parser.parse_args()
    package_name = os.getenv("PACKAGE_NAME", "cc.machado.audioblackbox")
    service_account_json = os.getenv("PLAY_STORE_JSON_KEY")
    
    if not service_account_json:
        print("ERROR: PLAY_STORE_JSON_KEY environment variable is required.")
        sys.exit(1)

    try:
        from google.oauth2 import service_account
        from googleapiclient.discovery import build
        from googleapiclient.http import MediaFileUpload
    except ImportError:
        print("ERROR: Missing google-api-python-client or google-auth. Run 'pip install google-api-python-client google-auth'")
        sys.exit(1)

    # Parse JSON credentials
    if os.path.isfile(service_account_json):
        credentials = service_account.Credentials.from_service_account_file(
            service_account_json,
            scopes=["https://www.googleapis.com/auth/androidpublisher"]
        )
    else:
        creds_info = json.loads(service_account_json)
        credentials = service_account.Credentials.from_service_account_info(
            creds_info,
            scopes=["https://www.googleapis.com/auth/androidpublisher"]
        )

    service = build("androidpublisher", "v3", credentials=credentials, cache_discovery=False)

    sync_metadata(service, package_name, MediaFileUpload, force=args.force)


def sync_metadata(service, package_name, media_upload,
                  metadata_base="distribution/metadata/android", force=False):
    from googleapiclient.errors import HttpError

    print(f"Starting Google Play Store metadata sync for package: {package_name}")

    # 1. Create a new edit session
    edit_response = service.edits().insert(packageName=package_name, body={}).execute()
    edit_id = edit_response["id"]
    print(f"Created Play Developer edit session ID: {edit_id}")

    if not os.path.isdir(metadata_base):
        print(f"Metadata directory '{metadata_base}' not found. Nothing to sync.")
        service.edits().delete(packageName=package_name, editId=edit_id).execute()
        return

    languages = [d for d in os.listdir(metadata_base) if os.path.isdir(os.path.join(metadata_base, d))]
    print(f"Found languages in repository: {languages}")

    changed = False
    for lang in languages:
        lang_dir = os.path.join(metadata_base, lang)
        print(f"\n--- Processing language: {lang} ---")

        # 2. Text metadata (title, short_description, full_description)
        title_file = os.path.join(lang_dir, "title.txt")
        short_desc_file = os.path.join(lang_dir, "short_description.txt")
        full_desc_file = os.path.join(lang_dir, "full_description.txt")

        listing_body = {}
        if os.path.isfile(title_file):
            with open(title_file, "r", encoding="utf-8") as f:
                listing_body["title"] = f.read().strip()
        if os.path.isfile(short_desc_file):
            with open(short_desc_file, "r", encoding="utf-8") as f:
                listing_body["shortDescription"] = f.read().strip()
        if os.path.isfile(full_desc_file):
            with open(full_desc_file, "r", encoding="utf-8") as f:
                listing_body["fullDescription"] = f.read().strip()

        if listing_body:
            try:
                remote = service.edits().listings().get(
                    packageName=package_name, editId=edit_id, language=lang
                ).execute()
            except HttpError as error:
                if error.resp.status != 404:
                    raise
                remote = None
            text_changed = remote is None or any(
                value != remote.get(key, "").strip() for key, value in listing_body.items()
            )
            print(f"{lang}/text: {'changed' if text_changed else 'unchanged'}"
                  + (" (forced sync)" if force else ""))
            if force or text_changed:
                service.edits().listings().update(
                    packageName=package_name, editId=edit_id,
                    language=lang, body=listing_body
                ).execute()
                changed = True

        # Keep the existing image type and sorted screenshot upload order.
        image_files = {
            "icon": glob.glob(os.path.join(lang_dir, "images", "icon.png")),
            "featureGraphic": glob.glob(os.path.join(lang_dir, "images", "featureGraphic.png")),
            "phoneScreenshots": sorted(glob.glob(os.path.join(lang_dir, "images", "phoneScreenshots", "*.png"))),
        }
        for image_type, files in image_files.items():
            params = dict(packageName=package_name, editId=edit_id,
                          language=lang, imageType=image_type)
            remote_images = service.edits().images().list(**params).execute()
            local_hashes = set()
            for filename in files:
                with open(filename, "rb") as image:
                    local_hashes.add(hashlib.sha256(image.read()).hexdigest())
            remote_hashes = {image.get("sha256") for image in remote_images.get("images", [])}
            images_changed = local_hashes != remote_hashes
            print(f"{lang}/{image_type}: {'changed' if images_changed else 'unchanged'}"
                  + (" (forced sync)" if force else ""))
            if force or images_changed:
                service.edits().images().deleteall(**params).execute()
                for filename in files:
                    media = media_upload(filename, mimetype="image/png")
                    service.edits().images().upload(**params, media_body=media).execute()
                changed = True

    if changed or force:
        print("\nCommitting changes to Google Play Developer API...")
        commit_response = service.edits().commit(packageName=package_name, editId=edit_id).execute()
        print(f"Successfully committed Play Store metadata edit! Commit ID: {commit_response.get('id', edit_id)}")
    else:
        service.edits().delete(packageName=package_name, editId=edit_id).execute()
        print("No listing change found; deleted edit. Nothing was committed.")

if __name__ == "__main__":
    main()
