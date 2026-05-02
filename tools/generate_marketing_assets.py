from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math
import random

OUT = Path("marketing")
OUT.mkdir(exist_ok=True)

FONT_REGULAR = r"C:\Windows\Fonts\msyh.ttc"
FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"

BG = (22, 14, 9)
PANEL = (38, 23, 14)
TEXT = (255, 244, 226)
SUB = (215, 185, 142)
ACC = (255, 180, 81)
ACC2 = (255, 211, 106)


def font(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT_REGULAR, size)


def gradient(size):
    w, h = size
    img = Image.new("RGB", size, BG)
    px = img.load()
    for y in range(h):
        for x in range(w):
            a = x / w
            b = y / h
            px[x, y] = (
                int(22 + 50 * a + 18 * (1 - b)),
                int(14 + 22 * a + 8 * (1 - b)),
                int(9 + 8 * a + 4 * (1 - b)),
            )
    return img.convert("RGBA")


def rounded(draw, box, radius, fill, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def draw_treehole(draw, cx, cy, scale=1.0):
    r = 150 * scale
    draw.ellipse([cx - r * 0.9, cy - r, cx + r * 0.9, cy + r], fill=(116, 59, 22))
    draw.ellipse([cx - r * 0.58, cy - r * 0.72, cx + r * 0.58, cy + r * 0.72], fill=(20, 11, 7))
    for i in range(5):
        rr = r * (0.25 + i * 0.13)
        draw.ellipse([cx - rr, cy - rr * 0.72, cx + rr, cy + rr * 0.72], outline=(255, 180, 81, 170), width=max(2, int(4 * scale)))
    pts = []
    for idx in range(61):
        t = idx / 60
        angle = -1.8 + 3.5 * t
        x = cx + math.cos(angle) * r * 0.32 + math.sin(t * math.pi) * r * 0.15
        y = cy + math.sin(angle) * r * 0.45
        pts.append((x, y))
    draw.line(pts, fill=ACC2, width=max(5, int(8 * scale)), joint="curve")


def draw_wave_line(draw, x1, y, x2, amp=16, seed=0):
    random.seed(seed)
    draw.line([x1, y, x2, y], fill=(255, 180, 81, 95), width=2)
    for i in range(36):
        p = i / 35
        x = x1 + p * (x2 - x1)
        yy = y + math.sin(i * 0.85 + seed) * amp * (0.35 + random.random() * 0.7)
        rad = 3 + random.random() * 4
        draw.ellipse([x - rad, yy - rad, x + rad, yy + rad], fill=(255, 190, 90, 150))


def wrap(draw, text, font_obj, max_width):
    lines = []
    cur = ""
    for ch in text:
        if ch == "\n":
            if cur:
                lines.append(cur)
                cur = ""
            continue
        nxt = cur + ch
        if draw.textlength(nxt, font=font_obj) > max_width:
            if cur:
                lines.append(cur)
            cur = ch
        else:
            cur = nxt
    if cur:
        lines.append(cur)
    return lines


def poster(size, name, title, subtitle, bullets, footer, kind="vertical"):
    img = gradient(size)
    w, h = size
    draw = ImageDraw.Draw(img)
    for i in range(18):
        random.seed(i + w + h)
        x = random.randint(-100, w + 100)
        y = random.randint(-100, h + 100)
        rr = random.randint(60, 190)
        draw.ellipse([x - rr, y - rr, x + rr, y + rr], fill=(255, 160, 70, 18))

    if kind == "wide":
        draw_treehole(draw, int(w * 0.20), int(h * 0.52), 0.82)
        tx = int(w * 0.39)
        ty = 54
        max_width = w - tx - 55
        title_size = 54
        sub_size = 26
        bullet_size = 21
    else:
        draw_treehole(draw, int(w * 0.50), int(h * 0.25), 1.15)
        tx = 80
        ty = int(h * 0.43)
        max_width = w - 160
        title_size = 64
        sub_size = 30
        bullet_size = 25

    draw.text((tx, ty), title, font=font(title_size, True), fill=TEXT)
    ty += 86 if kind != "wide" else 72
    for line in wrap(draw, subtitle, font(sub_size), max_width)[:3]:
        draw.text((tx, ty), line, font=font(sub_size), fill=SUB)
        ty += 42 if kind != "wide" else 36
    ty += 18
    for item in bullets:
        rounded(draw, [tx, ty, min(w - 55, tx + max_width), ty + 58], 18, (38, 23, 14, 205), outline=(255, 180, 81, 80), width=1)
        draw.text((tx + 22, ty + 13), item, font=font(bullet_size, True), fill=ACC2)
        ty += 74 if kind != "wide" else 64
    draw_wave_line(draw, tx, min(h - 116, ty + 20), min(w - 60, tx + max_width), 12, seed=len(title))
    if footer:
        draw.text((tx, h - 78), footer, font=font(24 if kind != "wide" else 21), fill=(255, 244, 226, 225))
    img.convert("RGB").save(OUT / name, quality=95)


def write_copy():
    text = """# 洞听播放器宣传文案

## 公众号标题备选
1. 我用 AI 辅助做了一个本地播放器：洞听播放器 v0.0.6
2. 能播放音频视频，也能朗读 TXT：洞听播放器做到了哪一步
3. 业余时间做的小工具：一个偏学习用途的本地播放器

## 公众号正文
大家好，我是朱振坚。

最近利用业余时间，在 AI 的辅助下做了一个本地播放器，名字叫「洞听播放器」。它不是商业软件，也不是成熟产品，主要是给自己学习、听课、听书、整理本地音视频用的一个小工具。

目前它已经支持本地音频和视频播放，可以扫描文件夹并自动生成播放列表；支持按文件夹记忆播放速度，最高 8 倍速；支持播放位置记忆、单曲循环、列表循环、AB 循环和书签。对于学习类音频、课程视频、反复听重点内容，这些功能会比较实用。

除了音视频，洞听播放器还加入了 TXT 朗读功能。导入文本后，可以用系统 TTS 朗读，支持暂停继续、分段朗读、朗读背景音乐，并且朗读文字会在播放界面像歌词一样滚动显示。

界面上，我给它做了一个暖橘色的树洞视觉风格：像一只耳朵在树洞里听声音。播放音频或朗读时，上方会显示树洞视觉舞台和声波线；播放视频时会切换成视频画面。

这个软件目前仅供个人学习研究使用，莫要用于商业销售。后续如果继续完善，可能会做歌词、字幕、均衡器、数据库媒体库和 Windows 版。

首发在公众号：小二菜园
微信和 QQ：254850837
祝大家学习愉快！

## 小红书标题备选
1. 我做了一个本地播放器，能听歌看视频还能朗读 TXT
2. 洞听播放器：适合听课、听书、复听重点的小工具
3. 用 AI 辅助做 App：我的本地播放器 v0.0.6

## 小红书正文
最近做了一个自己的本地播放器，叫「洞听播放器」。

它目前可以：
- 播放本地音频和视频
- 扫描文件夹，自动生成播放列表
- 记忆播放位置和倍速
- 最高 8 倍速播放
- 支持单曲循环、列表循环、AB 循环
- 支持 TXT 朗读和背景音乐
- 朗读文字可以像歌词一样滚动显示
- 视频有全屏和底部控制台

界面是暖橘色树洞风格，想表达的是“耳朵在树洞里听见声音”。

这是我个人利用 AI 在业余时间开发的小工具，仅供学习研究使用，莫要用于商业销售。

首发公众号：小二菜园
微信/QQ：254850837
"""
    (OUT / "promo-copy.md").write_text(text, encoding="utf-8")


poster((900, 383), "wechat-cover-900x383.png", "洞听播放器", "一个给本地音频、视频和 TXT 朗读准备的个人播放器", ["本地播放 · 文件夹列表", "8 倍速 · AB 循环 · 播放记忆", "TXT 朗读 · 背景音乐 · 树洞视觉"], "", "wide")
poster((1080, 1440), "wechat-article-card-1080x1440.png", "洞听播放器 v0.0.7", "本地音频、视频和 TXT 朗读工具", ["音频/视频播放", "文件夹扫描自动成列表", "单曲/列表/AB 循环", "TXT 朗读与背景音乐", "树洞暖橘视觉舞台"], "", "vertical")
poster((1242, 1660), "xiaohongshu-cover-1242x1660.png", "我做了一个本地播放器", "能听歌、看视频，也能朗读 TXT", ["本地文件夹扫描", "播放记忆和倍速记忆", "AB 循环和书签", "朗读文字像歌词滚动", "暖橘树洞界面"], "", "vertical")
poster((1242, 1660), "xiaohongshu-features-1242x1660.png", "洞听播放器能做什么？", "为学习、听课、听书和本地影音整理做的小工具", ["按文件夹自动生成播放列表", "音频/视频分开记忆倍速", "最高 8 倍速播放", "AB 循环适合复听重点", "通知栏后台播放控制"], "", "vertical")
poster((1080, 1080), "share-square-1080.png", "洞听播放器", "听歌 · 看视频 · 朗读 TXT", ["本地媒体库", "播放记忆", "倍速/循环/AB", "树洞视觉"], "", "vertical")
poster((1242, 1660), "notice-1242x1660.png", "开发说明", "洞听播放器由朱振坚个人利用 AI 在业余时间开发", ["仅供个人学习研究使用", "莫要用于商业销售", "首发在公众号：小二菜园", "微信和 QQ：254850837"], "", "vertical")
write_copy()

for path in sorted(OUT.iterdir()):
    print(path)
