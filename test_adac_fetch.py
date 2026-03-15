import requests
import re

url = "https://www.adac.de/verkehr/tanken-kraftstoff-antrieb/kraftstoffpreise/?query=35037"
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}

response = requests.get(url, headers=headers)
print(f"Status Code: {response.status_code}")

# Look for JSON-like structures in the HTML
json_match = re.search(r'window\.__INITIAL_STATE__\s*=\s*({.*?});', response.text)
if json_match:
    print("Found INITIAL_STATE!")
    with open("/tmp/adac_state.json", "w", encoding="utf-8") as f:
        f.write(json_match.group(1))
else:
    # Print some HTML if not found
    print("INITIAL_STATE not found. HTML Snippet:")
    print(response.text[:2000])
