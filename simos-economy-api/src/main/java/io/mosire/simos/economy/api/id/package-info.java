/**
 * 经济切片共用的**稳定 ID**（设计稿 §2/§3）。
 *
 * <p>每个 ID 都是"调用方给的短名 tag"——只作稳定身份，**不自增、不用随机 UUID**；裸值 {@code toString()} + {@code static parse} +
 * 空白即抛三件套（铁律 1：地址用于定位，ID 是身份）。改名/迁址不改 ID。
 *
 * <p>本包不含任何账本余额、面积、价格或公式。
 */
package io.mosire.simos.economy.api.id;
