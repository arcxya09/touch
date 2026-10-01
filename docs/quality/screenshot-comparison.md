# 截图比较

`scripts/check_design_screenshots.py` 使用 Pillow 比较两组 PNG，不修改基线或候选图片。输入按相对路径递归匹配；额外的 JSON、说明文件不参与比较。基线没有 PNG、缺失图片、多余图片、图片不可解码或尺寸不同，均失败，不受差异阈值放宽影响。

```sh
python scripts/check_design_screenshots.py --baseline baseline --candidate candidate --output comparison
```

默认逐像素 RGBA 完全相同才通过。`--pixel-threshold 8` 表示任意通道的绝对差值大于 8 才计作不同像素；`--max-diff-ratio 0.001` 表示每张图最多允许 0.1% 不同像素。两项阈值独立生效，边界值包含在允许范围中。阈值必须由审阅者明确决定，不能为让失败通过而自动调高。

退出码为 0（通过）、1（比较失败）、2（参数或目录配置错误）。结果写入输出目录的 `comparison.json`；有像素变化时写入 `diff/<相对文件名>`。差异图中白色亮度表示 RGBA 最大通道差值，红色标出超过像素阈值的位置。尺寸不符只记录双方尺寸，不拉伸图片。重复使用输出目录时，以最新 JSON 列出的差异图为准，未被引用的旧文件不代表本轮结果。

输出目录必须与两个输入目录互不包含，避免覆盖基线。比较不裁剪、不自动对齐、不忽略抗锯齿，也不转换 ICC 色彩配置。采集基线和候选时应固定设备、分辨率、字体缩放、主题、浏览器/系统版本及脱敏 fixture 内容；否则差异可能来自环境。自动通过只能证明在设置阈值内一致，不能替代设计审阅。

基线更新必须另行人工审阅并提交。工具没有更新或接受基线的选项。后台本地烟测截图见 [admin-screenshots](admin-screenshots/README.md)，它们尚未被批准作为正式比较基线。

合成图回归：`python -m unittest discover -s scripts -p test_check_design_screenshots.py -v`。CI 和草稿构建已接入该回归；对实际产品截图的视觉比较仍需提供审阅过的基线和新采集结果。
