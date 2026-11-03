import asyncio
from playwright.async_api import async_playwright

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        iphone = p.devices["iPhone 13"]
        ctx = await b.new_context(**{k: v for k, v in iphone.items() if k != "default_browser_type"})
        page = await ctx.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.on("console", lambda m: m.type == "error" and errors.append(m.text))
        await page.goto("http://127.0.0.1:8787/?k=testkey123")
        await page.wait_for_timeout(4000)
        print("STATUS:", (await page.text_content("#status")).replace("\n", " | "))
        await page.screenshot(path="/tmp/map3d.png")
        # one-finger drag (touch) pans and leaves follow mode
        box = await page.locator("#map").bounding_box()
        cx, cy = box["x"] + box["width"] / 2, box["y"] + box["height"] / 2
        await page.mouse.move(cx, cy); await page.mouse.down(); await page.mouse.move(cx - 80, cy - 40, steps=10); await page.mouse.up()
        await page.wait_for_timeout(600)
        await page.click("#tFollow"); await page.wait_for_timeout(1200)
        await page.click("#b3d"); await page.wait_for_timeout(1500)
        await page.screenshot(path="/tmp/map2d.png")
        # tap the first list row, check track text
        await page.locator("#list .item[data-key]").first.click(); await page.wait_for_timeout(400)
        print("TRACK:", (await page.text_content("#track")).replace("\n", " | "))
        await page.get_by_role("button", name="POLICE/ALPR").click(); await page.wait_for_timeout(500)
        print("POLICE rows:", await page.locator("#list .item[data-key]").count())
        p2 = await ctx.new_page(); p2.on("pageerror", lambda e: errors.append("demo: " + str(e)))
        await p2.goto("file:///home/claude/airscope/app/src/main/assets/hud.html"); await p2.wait_for_timeout(2500)
        print("DEMO:", (await p2.text_content("#status")).split("\n")[0])
        await p2.screenshot(path="/tmp/mapdemo.png")
        print("JS ERRORS:", errors or "none")
        await b.close()
asyncio.run(main())
