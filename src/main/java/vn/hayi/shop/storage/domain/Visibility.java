package vn.hayi.shop.storage.domain;

/** Who may read an object (Storage §18.7); knowing its key never grants access. */
public enum Visibility {

	PUBLIC, PRIVATE, RESTRICTED

}
