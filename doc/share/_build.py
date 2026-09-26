# -*- coding: utf-8 -*-
"""把博文 markdown 转成自包含 HTML（5 张架构图 base64 内嵌），供公众号一键分享。临时脚本，跑完删除。"""
import base64
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
MD = os.path.join(HERE, "langur-agent-harness-jev-rsi-architecture.md")
IMG_DIR = os.path.join(HERE, "images")
OUT = os.path.join(HERE, "langur-agent-harness-jev-rsi-architecture.html")


def inline_img(match):
    alt, rel = match.group(1), match.group(2)
    p = os.path.join(IMG_DIR, os.path.basename(rel))
    if not os.path.exists(p):
        return '<img alt="%s">' % alt
    with open(p, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    return '<img alt="%s" src="data:image/png;base64,%s" style="max-width:100%%;">' % (alt, b64)


def inline(seg):
    seg = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2" style="color:#2563eb;">\1</a>', seg)
    seg = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", seg)
    seg = re.sub(r"`([^`]+)`", r"<code>\1</code>", seg)
    return seg


def convert(text):
    lines = text.split("\n")
    out = []
    i = 0
    n = len(lines)
    while i < n:
        line = lines[i]
        # 代码块
        if line.strip().startswith("```"):
            out.append("<pre><code>")
            i += 1
            while i < n and not lines[i].strip().startswith("```"):
                out.append(lines[i].replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                i += 1
            i += 1  # skip closing ```
            out.append("</code></pre>")
            continue
        # 图片
        m = re.match(r"^!\[([^\]]*)\]\(([^)]+)\)\s*$", line.strip())
        if m:
            out.append(inline_img(m))
            i += 1
            continue
        # 标题
        m = re.match(r"^(#{1,3})\s+(.*)$", line)
        if m:
            level = len(m.group(1))
            out.append("<h%d>%s</h%d>" % (level, inline(m.group(2)), level))
            i += 1
            continue
        # 引用（连续 > 合并）
        if line.startswith(">"):
            out.append("<blockquote>")
            while i < n and lines[i].startswith(">"):
                out.append("<p>%s</p>" % inline(lines[i][1:].strip()))
                i += 1
            out.append("</blockquote>")
            continue
        # 无序列表
        if re.match(r"^\s*-\s+", line):
            out.append("<ul>")
            while i < n and re.match(r"^\s*-\s+", lines[i]):
                out.append("<li>%s</li>" % inline(re.sub(r"^\s*-\s+", "", lines[i])))
                i += 1
            out.append("</ul>")
            continue
        # 有序列表
        if re.match(r"^\s*\d+\.\s+", line):
            out.append("<ol>")
            while i < n and re.match(r"^\s*\d+\.\s+", lines[i]):
                out.append("<li>%s</li>" % inline(re.sub(r"^\s*\d+\.\s+", "", lines[i])))
                i += 1
            out.append("</ol>")
            continue
        # 空行
        if line.strip() == "":
            i += 1
            continue
        # 普通段落
        out.append("<p>%s</p>" % inline(line.strip()))
        i += 1
    return "\n".join(out)


def main():
    with open(MD, "r", encoding="utf-8") as f:
        md = f.read()
    body = convert(md)
    html = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Langur Agent-Harness：三条架构哲学</title>
<style>
  body { max-width: 720px; margin: 0 auto; padding: 24px 16px; font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif; color: #1f2937; line-height: 1.85; font-size: 16px; }
  h1 { font-size: 24px; line-height: 1.4; }
  h2 { font-size: 20px; margin-top: 1.8em; border-left: 4px solid #2563eb; padding-left: 12px; }
  h3 { font-size: 17px; }
  blockquote { border-left: 3px solid #d1d5db; margin: 1em 0; padding: 2px 0 2px 14px; color: #4b5563; }
  img { max-width: 100%; height: auto; margin: 12px 0; }
  code { background: #f3f4f6; padding: 1px 6px; border-radius: 4px; font-size: 14px; font-family: 'SFMono-Regular', Consolas, monospace; }
  pre { background: #f6f8fa; padding: 14px 16px; border-radius: 6px; overflow-x: auto; line-height: 1.6; }
  pre code { background: none; padding: 0; }
  ul, ol { padding-left: 1.6em; }
  li { margin: 4px 0; }
  strong { color: #111827; }
</style>
</head>
<body>
__BODY__
</body>
</html>
""".replace("__BODY__", body)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(html)
    size = os.path.getsize(OUT)
    print("generated", OUT, "%.1f KB" % (size / 1024))


if __name__ == "__main__":
    main()
