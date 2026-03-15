import requests
from bs4 import BeautifulSoup
import json

url = "https://www.adac.de/verkehr/tanken-kraftstoff-antrieb/kraftstoffpreise/?query=35037"
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}

response = requests.get(url, headers=headers)
soup = BeautifulSoup(response.text, 'html.parser')

scripts = soup.find_all('script')
for i, script in enumerate(scripts):
    if script.string:
        with open(f"/tmp/adac_script_{i}.js", "w", encoding="utf-8") as f:
            f.write(script.string)

print(f"Dumped {len(scripts)} scripts to /tmp/")
