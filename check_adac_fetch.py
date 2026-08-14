"""Manual probe for the ADAC price page.

This is a developer tool, not a test: it performs a live HTTP request. It used to
be named ``test_adac_fetch.py``, which made ``pytest`` execute the request during
collection and turned the release quality gate into a network-dependent step.
Run it explicitly with ``python check_adac_fetch.py``.
"""

import re

import requests

URL = "https://www.adac.de/verkehr/tanken-kraftstoff-antrieb/kraftstoffpreise/?query=35037"
HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )
}


def main() -> None:
    response = requests.get(URL, headers=HEADERS, timeout=20)
    print(f"Status Code: {response.status_code}")

    # Look for JSON-like structures in the HTML
    json_match = re.search(r"window\.__INITIAL_STATE__\s*=\s*({.*?});", response.text)
    if json_match:
        print("Found INITIAL_STATE!")
        with open("adac_state.json", "w", encoding="utf-8") as f:
            f.write(json_match.group(1))
    else:
        print("INITIAL_STATE not found. HTML Snippet:")
        print(response.text[:2000])


if __name__ == "__main__":
    main()
