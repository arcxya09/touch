"""Compare immutable PNG screenshot sets; write evidence, never update a baseline."""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageChops


def screenshots(folder):
    return {path.relative_to(folder).as_posix(): path for path in folder.rglob("*")
            if path.is_file() and path.suffix.lower() == ".png"}


def compare(baseline, candidate, output, pixel_threshold=0, max_diff_ratio=0.0):
    baseline, candidate, output = (Path(path).resolve() for path in (baseline, candidate, output))
    if not baseline.is_dir() or not candidate.is_dir():
        raise ValueError("Baseline and candidate must be existing directories")
    if not 0 <= pixel_threshold <= 255 or not 0 <= max_diff_ratio <= 1:
        raise ValueError("Pixel threshold must be 0..255 and maximum difference ratio 0..1")
    if any(output.is_relative_to(folder) or folder.is_relative_to(output) for folder in (baseline, candidate)):
        raise ValueError("Output must be outside both input directories, with no directory overlap")
    expected, actual = screenshots(baseline), screenshots(candidate)
    output.mkdir(parents=True, exist_ok=True)
    results = []
    for name in sorted(expected.keys() | actual.keys()):
        result = {"file": name, "passed": False}
        results.append(result)
        if name not in actual:
            result["status"] = "missing"
            continue
        if name not in expected:
            result["status"] = "unexpected"
            continue
        try:
            with Image.open(expected[name]) as left, Image.open(actual[name]) as right:
                if left.format != "PNG" or right.format != "PNG":
                    raise ValueError("Screenshot files must contain PNG images")
                result.update(baseline_size=list(left.size), candidate_size=list(right.size))
                if left.size != right.size:
                    result["status"] = "size_mismatch"
                    continue
                # Include alpha, without rescaling, alignment, antialias masking or color-profile conversion.
                difference = ImageChops.difference(left.convert("RGBA"), right.convert("RGBA"))
                channels = difference.split()
                maximum = channels[0]
                for channel in channels[1:]:
                    maximum = ImageChops.lighter(maximum, channel)
                mask = maximum.point(lambda value: 255 if value > pixel_threshold else 0)
                changed = mask.histogram()[255]
                pixels = left.width * left.height
                ratio = changed / pixels
                passed = ratio <= max_diff_ratio
                result.update(status="matched" if changed == 0 else "within_tolerance" if passed else "different",
                              passed=passed, changed_pixels=changed, total_pixels=pixels, diff_ratio=ratio,
                              max_channel_difference=maximum.getextrema()[1])
                if maximum.getextrema()[1] > 0:
                    destination = output / "diff" / name
                    if not destination.resolve().is_relative_to(output):
                        raise ValueError("Difference output must not escape through a symbolic link")
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    # White intensity shows maximum RGBA difference; red marks pixels exceeding tolerance.
                    visual = Image.merge("RGB", (ImageChops.lighter(maximum, mask), maximum, maximum))
                    visual.save(destination, format="PNG")
                    result["diff_image"] = destination.relative_to(output).as_posix()
        except (OSError, ValueError, Image.DecompressionBombError) as error:
            result.update(status="invalid_image", error=str(error))
    report = {"schema_version": 1, "baseline": str(baseline), "candidate": str(candidate),
              "pixel_threshold": pixel_threshold, "max_diff_ratio": max_diff_ratio,
              "passed": bool(expected) and all(row["passed"] for row in results),
              "expected_count": len(expected), "candidate_count": len(actual), "results": results}
    if not expected:
        report["error"] = "Baseline contains no PNG screenshots"
    report_path = output / "comparison.json"
    if not report_path.resolve().is_relative_to(output):
        raise ValueError("JSON output must not escape through a symbolic link")
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--pixel-threshold", type=int, default=0,
                        help="A pixel differs when any RGBA channel exceeds this absolute 0..255 difference")
    parser.add_argument("--max-diff-ratio", type=float, default=0.0,
                        help="Maximum fraction of differing pixels per image (0..1); default is exact match")
    args = parser.parse_args(argv)
    try:
        report = compare(args.baseline, args.candidate, args.output, args.pixel_threshold, args.max_diff_ratio)
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps({"passed": report["passed"], "report": str(args.output / "comparison.json")}, ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
