;; Adapted from https://github.com/sweetppro/zed-xml (MIT License, Copyright (c) 2025 SweetPPro).

(STag ">" @end) @indent
(EmptyElemTag "/>" @end) @indent

(element
  (STag) @start
  (ETag)? @end) @indent
