package com.example.finance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.ToString;

/**
 * 统一API响应包装类，所有接口返回此结构以保证前后端协议一致。
 *
 * @param <T> 响应数据类型
 */
@Getter
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** 请求是否成功 */
    private final boolean success;

    /** 响应数据（成功时有值） */
    private final T data;

    /** 错误信息（失败时有值） */
    private final String message;

    private ApiResponse(boolean success, T data, String message) {
        this.success = success;
        this.data = data;
        this.message = message;
    }

    /** 构造成功响应 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /** 构造错误响应 */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
