#!/usr/bin/env python3
"""
Syncs store listing metadata (titles, short/full descriptions, icon, feature graphic, screenshots)
from distribution/metadata/android/<language>/ to the Google Play Developer API (androidpublisher v3).
"""

import os
import sys
import json
import glob

def main():
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

    print(f"Starting Google Play Store metadata sync for package: {package_name}")

    # 1. Create a new edit session
    edit_response = service.edits().insert(packageName=package_name, body={}).execute()
    edit_id = edit_response["id"]
    print(f"Created Play Developer edit session ID: {edit_id}")

    metadata_base = "distribution/metadata/android"
    if not os.path.isdir(metadata_base):
        print(f"Metadata directory '{metadata_base}' not found. Nothing to sync.")
        return

    languages = [d for d in os.listdir(metadata_base) if os.path.isdir(os.path.join(metadata_base, d))]
    print(f"Found languages in repository: {languages}")

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
            print(f"Updating store listing text for '{lang}' (title: '{listing_body.get('title')}')")
            service.edits().listings().update(
                packageName=package_name,
                editId=edit_id,
                language=lang,
                body=listing_body
            ).execute()

        # 3. Icon (512x512 PNG)
        icon_file = os.path.join(lang_dir, "images", "icon.png")
        if os.path.isfile(icon_file):
            print(f"Uploading app icon for '{lang}'...")
            media = MediaFileUpload(icon_file, mimetype="image/png")
            service.edits().images().upload(
                packageName=package_name,
                editId=edit_id,
                language=lang,
                imageType="icon",
                media_body=media
            ).execute()

        # 4. Feature Graphic (1024x500 PNG)
        fg_file = os.path.join(lang_dir, "images", "featureGraphic.png")
        if os.path.isfile(fg_file):
            print(f"Uploading feature graphic for '{lang}'...")
            media = MediaFileUpload(fg_file, mimetype="image/png")
            service.edits().images().upload(
                packageName=package_name,
                editId=edit_id,
                language=lang,
                imageType="featureGraphic",
                media_body=media
            ).execute()

        # 5. Phone Screenshots
        screenshots_dir = os.path.join(lang_dir, "images", "phoneScreenshots")
        if os.path.isdir(screenshots_dir):
            screenshots = sorted(glob.glob(os.path.join(screenshots_dir, "*.png")))
            if screenshots:
                print(f"Found {len(screenshots)} phone screenshots for '{lang}'. Refreshing...")
                try:
                    service.edits().images().deleteall(
                        packageName=package_name,
                        editId=edit_id,
                        language=lang,
                        imageType="phoneScreenshots"
                    ).execute()
                except Exception as e:
                    print(f"Notice: deleteall screenshots returned {e}")

                for shot in screenshots:
                    print(f"  Uploading screenshot: {os.path.basename(shot)}")
                    media = MediaFileUpload(shot, mimetype="image/png")
                    service.edits().images().upload(
                        packageName=package_name,
                        editId=edit_id,
                        language=lang,
                        imageType="phoneScreenshots",
                        media_body=media
                    ).execute()

    # 6. Commit the entire edit session
    print("\nCommitting changes to Google Play Developer API...")
    commit_response = service.edits().commit(packageName=package_name, editId=edit_id).execute()
    print(f"Successfully committed Play Store metadata edit! Commit ID: {commit_response.get('id', edit_id)}")

if __name__ == "__main__":
    main()
