# hayi-shop-backend

Backend HaYi Shop — Spring Boot modular monolith (Spring Modulith), MySQL 8.0, Flyway.

Tài liệu chuẩn (nguồn sự thật nghiệp vụ & thiết kế): `../../HaYi-Shop-Canonical-VI/`
— đặc biệt `docs/architecture/SOURCE-STRUCTURE.md`, `IMPLEMENTATION-PLAN.md`, `erd/`, `api/`.

## Yêu cầu

- JDK 25 (`brew install openjdk@25`, rồi `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`)
- Docker (MySQL 8.0 + SeaweedFS cho local, Testcontainers cho test)

## Lệnh thường dùng

```bash
./gradlew build                 # compile + toàn bộ test (cần Docker)
./gradlew bootRun               # chạy app; tự khởi động compose.yaml (MySQL, SeaweedFS)
./gradlew test --tests '*ModularityTest'   # kiểm tra ranh giới module
```

Chạy với Testcontainers thay vì compose: chạy `TestHayiShopApplication` (src/test).

## Owner đầu tiên & khóa ký JWT

- **Owner bootstrap** (Decision K1): trên môi trường mới, khởi động app một lần với
  `HAYI_BOOTSTRAP_OWNER_EMAIL` + `HAYI_BOOTSTRAP_OWNER_PASSWORD` (mật khẩu tạm, ≥ 12 ký tự). App tạo OWNER,
  bắt đổi mật khẩu ở lần đăng nhập đầu; đã có OWNER thì bỏ qua. Xóa hai biến sau khi xong.
- **Khóa ký JWT**: `HAYI_SECURITY_SIGNING_KEY` = JWK riêng tư P-256 (JSON, có `kid`). `./gradlew bootRun` và test dùng
  khóa tạm sinh lúc khởi động (`hayi.security.allow-ephemeral-key=true`), nên phiên mất khi restart.

```bash
HAYI_BOOTSTRAP_OWNER_EMAIL=owner@hayi.local HAYI_BOOTSTRAP_OWNER_PASSWORD='mat khau tam thoi 01' ./gradlew bootRun
```

## Cấu trúc

```text
src/main/java/vn/hayi/shop/
├── shared/      shared kernel (OPEN module)
├── platform/    hạ tầng kỹ thuật (OPEN module)
└── <context>/   17 bounded context: api / web / application / domain / infrastructure
api-spec/        OpenAPI 3.1 design-first (public, customer, backoffice)
src/main/resources/db/migration/   Flyway, tạo theo slice (DDL từ ERD)
```

Quy tắc: module khác chỉ được dùng package `<context>.api`. `ModularityTest` fail nếu vi phạm.

## Local storage

MinIO không còn phát hành image → dùng **SeaweedFS** (S3-compatible). Credential trong
`docker/seaweedfs/s3.json` chỉ dành cho dev.
