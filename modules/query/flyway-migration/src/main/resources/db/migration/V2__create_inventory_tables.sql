-- 商品マスターテーブル
create table "products"
(
    "id"                 char(26)                  not null primary key,
    "product_code"       varchar(50)               not null unique,
    "name"               varchar(200)              not null,
    "category_code"      varchar(50)               not null,
    "storage_condition"  varchar(20)               not null check (storage_condition in ('RT', 'RF', 'FZ')),
    "is_obsolete"        boolean                   not null default false,
    "created_at"         timestamp with time zone  not null default current_timestamp,
    "updated_at"         timestamp with time zone  not null default current_timestamp
);

create index "idx_products_product_code" on "products" ("product_code");
create index "idx_products_category_code" on "products" ("category_code");
create index "idx_products_storage_condition" on "products" ("storage_condition");
create index "idx_products_is_obsolete" on "products" ("is_obsolete");

comment on table "products" is '商品マスター（8,000種類のSKUを管理）';
comment on column "products"."id" is '商品ID（ULID）';
comment on column "products"."product_code" is '商品コード';
comment on column "products"."name" is '商品名';
comment on column "products"."category_code" is 'カテゴリーコード';
comment on column "products"."storage_condition" is '保管条件（RT:常温, RF:冷蔵, FZ:冷凍）';
comment on column "products"."is_obsolete" is '廃番フラグ';

-- 取引先マスターテーブル
create table "customers"
(
    "id"              char(26)                  not null primary key,
    "customer_code"   varchar(50)               not null unique,
    "name"            varchar(200)              not null,
    "customer_type"   varchar(20)               not null check (customer_type in ('LARGE', 'MEDIUM', 'SMALL')),
    "is_active"       boolean                   not null default true,
    "created_at"      timestamp with time zone  not null default current_timestamp,
    "updated_at"      timestamp with time zone  not null default current_timestamp
);

create index "idx_customers_customer_code" on "customers" ("customer_code");
create index "idx_customers_customer_type" on "customers" ("customer_type");
create index "idx_customers_is_active" on "customers" ("is_active");

comment on table "customers" is '取引先マスター（約430社を管理）';
comment on column "customers"."id" is '取引先ID（ULID）';
comment on column "customers"."customer_code" is '取引先コード';
comment on column "customers"."name" is '取引先名';
comment on column "customers"."customer_type" is '取引先タイプ（LARGE:大口, MEDIUM:中口, SMALL:小口）';
comment on column "customers"."is_active" is '有効フラグ';

-- 倉庫マスターテーブル
create table "warehouses"
(
    "id"              char(26)                  not null primary key,
    "warehouse_code"  varchar(50)               not null unique,
    "name"            varchar(200)              not null,
    "location"        varchar(200)              not null,
    "is_active"       boolean                   not null default true,
    "created_at"      timestamp with time zone  not null default current_timestamp,
    "updated_at"      timestamp with time zone  not null default current_timestamp
);

create index "idx_warehouses_warehouse_code" on "warehouses" ("warehouse_code");
create index "idx_warehouses_is_active" on "warehouses" ("is_active");

comment on table "warehouses" is '倉庫マスター（東京、大阪、福岡の3拠点）';
comment on column "warehouses"."id" is '倉庫ID（ULID）';
comment on column "warehouses"."warehouse_code" is '倉庫コード';
comment on column "warehouses"."name" is '倉庫名';
comment on column "warehouses"."location" is '所在地';
comment on column "warehouses"."is_active" is '有効フラグ';

-- 倉庫ゾーンマスターテーブル
create table "warehouse_zones"
(
    "id"                 char(26)                  not null primary key,
    "warehouse_id"       char(26)                  not null references "warehouses" ("id"),
    "zone_code"          varchar(50)               not null,
    "name"               varchar(200)              not null,
    "zone_type"          varchar(20)               not null check (zone_type in ('RT', 'RF', 'FZ')),
    "capacity_sqm"       numeric(10, 2)            not null check (capacity_sqm > 0),
    "is_active"          boolean                   not null default true,
    "created_at"         timestamp with time zone  not null default current_timestamp,
    "updated_at"         timestamp with time zone  not null default current_timestamp,
    unique ("warehouse_id", "zone_code")
);

create index "idx_warehouse_zones_warehouse_id" on "warehouse_zones" ("warehouse_id");
create index "idx_warehouse_zones_zone_code" on "warehouse_zones" ("zone_code");
create index "idx_warehouse_zones_zone_type" on "warehouse_zones" ("zone_type");
create index "idx_warehouse_zones_is_active" on "warehouse_zones" ("is_active");

comment on table "warehouse_zones" is '倉庫ゾーンマスター（各倉庫3ゾーン、全9ゾーン）';
comment on column "warehouse_zones"."id" is '倉庫ゾーンID（ULID）';
comment on column "warehouse_zones"."warehouse_id" is '倉庫ID';
comment on column "warehouse_zones"."zone_code" is 'ゾーンコード';
comment on column "warehouse_zones"."name" is 'ゾーン名';
comment on column "warehouse_zones"."zone_type" is 'ゾーンタイプ（RT:常温, RF:冷蔵, FZ:冷凍）';
comment on column "warehouse_zones"."capacity_sqm" is '収容能力（平方メートル）';
comment on column "warehouse_zones"."is_active" is '有効フラグ';

-- 在庫情報テーブル
create table "inventories"
(
    "id"                  char(26)                  not null primary key,
    "product_id"          char(26)                  not null references "products" ("id"),
    "warehouse_zone_id"   char(26)                  not null references "warehouse_zones" ("id"),
    "available_quantity"  numeric(15, 3)            not null default 0 check (available_quantity >= 0),
    "reserved_quantity"   numeric(15, 3)            not null default 0 check (reserved_quantity >= 0),
    "version"             bigint                    not null default 1,
    "created_at"          timestamp with time zone  not null default current_timestamp,
    "updated_at"          timestamp with time zone  not null default current_timestamp,
    unique ("product_id", "warehouse_zone_id")
);

create index "idx_inventories_product_id" on "inventories" ("product_id");
create index "idx_inventories_warehouse_zone_id" on "inventories" ("warehouse_zone_id");
create index "idx_inventories_available_quantity" on "inventories" ("available_quantity");

comment on table "inventories" is '在庫情報（商品×倉庫ゾーンの組み合わせごとの在庫）';
comment on column "inventories"."id" is '在庫ID（ULID）';
comment on column "inventories"."product_id" is '商品ID';
comment on column "inventories"."warehouse_zone_id" is '倉庫ゾーンID';
comment on column "inventories"."available_quantity" is '利用可能在庫数量';
comment on column "inventories"."reserved_quantity" is '引当済み在庫数量';
comment on column "inventories"."version" is 'バージョン（楽観的ロック用）';

-- 在庫トランザクション履歴テーブル
create table "inventory_transactions"
(
    "id"                      char(26)                  not null primary key,
    "inventory_id"            char(26)                  not null references "inventories" ("id"),
    "transaction_type"        varchar(20)               not null check (transaction_type in ('RECEIVED', 'ISSUED', 'RESERVED', 'RELEASED', 'ADJUSTED', 'MOVED')),
    "quantity"                numeric(15, 3)            not null,
    "from_warehouse_zone_id"  char(26)                  references "warehouse_zones" ("id"),
    "to_warehouse_zone_id"    char(26)                  references "warehouse_zones" ("id"),
    "reason"                  text,
    "occurred_at"             timestamp with time zone  not null,
    "created_at"              timestamp with time zone  not null default current_timestamp
);

create index "idx_inventory_transactions_inventory_id" on "inventory_transactions" ("inventory_id");
create index "idx_inventory_transactions_transaction_type" on "inventory_transactions" ("transaction_type");
create index "idx_inventory_transactions_occurred_at" on "inventory_transactions" ("occurred_at");

comment on table "inventory_transactions" is '在庫トランザクション履歴（1日2,000件のトランザクション）';
comment on column "inventory_transactions"."id" is 'トランザクションID（ULID）';
comment on column "inventory_transactions"."inventory_id" is '在庫ID';
comment on column "inventory_transactions"."transaction_type" is 'トランザクション種別';
comment on column "inventory_transactions"."quantity" is '数量';
comment on column "inventory_transactions"."from_warehouse_zone_id" is '移動元倉庫ゾーンID（移動時のみ）';
comment on column "inventory_transactions"."to_warehouse_zone_id" is '移動先倉庫ゾーンID（移動時のみ）';
comment on column "inventory_transactions"."reason" is '理由（調整時など）';
comment on column "inventory_transactions"."occurred_at" is '発生日時';
