#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性脚本：把 Java Javadoc 注释里的 HTML 标签清成纯文本。

背景：用户看的是 IDEA 源码视图（不渲染 Javadoc），<h3> / <b> / <ol><li> / <pre> / <p>
到他那儿就是一堆标签噪音。要求注释里不出现 HTML。

转换规则（只作用于以 * 开头的 Javadoc 注释行）：
  1) 独占一行的块标签 <p> </p> <pre> </pre> <ol> </ol> <ul> </ul>  -> 变成空注释行
  2) <h1>~<h6>标题</h*>                                          -> 标题文字
  3) <li>内容</li>                                               -> - 内容（保留缩进）
  4) 行内 <b> </b> <i> </i> <em> <strong> <code> </code> <tt>      -> 直接删除标签
  5) &gt; -> >   （安全，纯文本下更可读）

刻意不做的事：
  - 不还原 &lt; / &amp; / &#47;  —— &#47; 是故意用来写 */ 的转义，还原会让 Javadoc 提前结束
  - 不碰 {@code} {@link} @param @return @see —— 这些是 Javadoc 标准标签不是 HTML
  - 不碰非 Javadoc 行（不以 * 开头的行）
  - 不改变行尾（CRLF 原样保留，否则整文件 diff）

用法：
  python strip_javadoc_html.py <root>              # dry-run，只报告不改
  python strip_javadoc_html.py <root> --apply      # 实际写入
"""
import io
import os
import re
import sys

STANDALONE_BLOCK = re.compile(r'^(\s*\*)[ \t]*(?:</?(?:p|pre|ol|ul)>[ \t]*)+$')
LEAD_BLOCK_TAG = re.compile(r'^(\s*\*)[ \t]*</?(?:p|pre|ol|ul)>[ \t]*')
TRAIL_BLOCK_TAG = re.compile(r'</(?:p|pre|ol|ul|li|h[1-6])>[ \t]*$')
HEADING = re.compile(r'<h[1-6]>(.*?)</h[1-6]>')
LIST_ITEM = re.compile(r'^(\s*\*)[ \t]*<li>(.*?)</li>[ \t]*$')
INLINE = re.compile(r'</?(?:b|i|em|strong|code|tt|u|h[1-6])>')
JAVADOC_LINE = re.compile(r'^\s*\*')


def split_eol(line):
    for eol in ('\r\n', '\n', '\r'):
        if line.endswith(eol):
            return line[:-len(eol)], eol
    return line, ''


def convert_body(body):
    original = body
    # 1) 独占一行的块标签 -> 空注释行
    if STANDALONE_BLOCK.match(body):
        return STANDALONE_BLOCK.sub(r'\1', body), 1
    # 2) 行首块标签（可能后面还跟着内容）
    body = LEAD_BLOCK_TAG.sub(r'\1 ', body)
    # 3) <li>内容</li> -> " - 内容"（保留前面的缩进）
    m = LIST_ITEM.match(body)
    if m:
        body = m.group(1) + ' - ' + m.group(2)
    # 4) 行尾的闭合块标签
    body = TRAIL_BLOCK_TAG.sub('', body)
    # 5) 标题标签 -> 标题文字
    body = HEADING.sub(r'\1', body)
    # 6) 行内标签
    body = INLINE.sub('', body)
    # 7) 实体（&#47; 刻意不动，它是用来写 */ 的转义）
    body = body.replace('&gt;', '>')
    return body, (1 if body != original else 0)


def process_file(path, apply_changes):
    with io.open(path, encoding='utf-8', newline='') as f:
        lines = f.readlines()
    out = []
    hit = 0
    for idx, line in enumerate(lines, 1):
        body, eol = split_eol(line)
        if JAVADOC_LINE.match(body) and ('<' in body or '&gt;' in body):
            new_body, changed = convert_body(body)
            if changed:
                hit += 1
                out.append(new_body + eol)
                continue
        out.append(line)
    if hit and apply_changes:
        with io.open(path, 'w', encoding='utf-8', newline='') as f:
            f.writelines(out)
    return hit


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    apply_changes = '--apply' in sys.argv
    root = args[0] if args else '.'
    total = 0
    files = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d != 'target']
        for name in filenames:
            if not name.endswith('.java'):
                continue
            path = os.path.join(dirpath, name)
            hit = process_file(path, apply_changes)
            if hit:
                files += 1
                total += hit
                print('%-6s %-3d  %s' % ('APPLY' if apply_changes else 'DRY', hit,
                                         os.path.relpath(path, root)))
    print('---')
    print('%s: %d 处，%d 个文件' % ('已写入' if apply_changes else '待改', total, files))


if __name__ == '__main__':
    main()
