#!/usr/bin/env python3
"""공공데이터포털 '한국도로공사_졸음쉼터' CSV 를 앱의 assets/rest_areas.json 형식으로 바꾼다.

데이터: https://www.data.go.kr/data/15028203/fileData.do (한국도로공사, CSV)
사용:  python3 tools/rest-areas/build_rest_areas.py <졸음쉼터.csv>
휴게소(SERVICE_AREA)는 기존 JSON 의 항목을 그대로 두고, 졸음쉼터(DROWSY_SHELTER)만 CSV 로 교체한다.
"""
import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "rest_areas.json")
SHELTER_RADIUS_M = 150


def main(csv_path):
    raw = open(csv_path, "rb").read()
    for enc in ("utf-8-sig", "cp949"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    rows = list(csv.DictReader(text.splitlines()))
    shelters, skipped = [], 0
    for i, r in enumerate(rows, 1):
        try:
            lat, lng = float(r["위도"]), float(r["경도"])
        except (KeyError, ValueError):
            skipped += 1
            continue
        if not (33.0 <= lat <= 39.0 and 124.0 <= lng <= 132.0):  # 대한민국 범위 밖 좌표는 버린다
            skipped += 1
            continue
        route = (r.get("도로노선명") or "").strip()
        shelters.append({
            "id": f"shelter-{i}",
            "name": f"{r['졸음쉼터명'].strip()} 졸음쉼터" + (f" ({route})" if route else ""),
            "type": "DROWSY_SHELTER",
            "lat": round(lat, 6),
            "lng": round(lng, 6),
            "radiusM": SHELTER_RADIUS_M,
        })
    existing = json.load(open(OUT, encoding="utf-8"))
    service_areas = [a for a in existing if a["type"] == "SERVICE_AREA"]
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(service_areas + shelters, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"휴게소 {len(service_areas)}곳 + 졸음쉼터 {len(shelters)}곳 저장 (제외 {skipped}건) → {os.path.normpath(OUT)}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
