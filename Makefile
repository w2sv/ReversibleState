include scripts/Makefile

copy-diff-to-clipboard:
	@git add -N .
	@git diff | wl-copy

copy-head-to-clipboard:
	@git show HEAD | wl-copy
