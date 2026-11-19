;; Adapted from https://github.com/sweetppro/zed-xml (MIT License, Copyright (c) 2025 SweetPPro).

(element
  (STag
    (Name) @name)) @item

(EmptyElemTag
  (Name) @name) @item

(doctypedecl
  (Name) @name) @item