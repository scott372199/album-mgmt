package top.chenyouyi.album.mgmt.provider.aws;

/**
 * 数据源明确不可用（配置缺失、快照过期、上游返回空等）。消息只写可公开的原因，
 * 不带上游异常文本，避免把 ARN、账号或内部对象 key 泄进 HTTP 响应。
 *
 * A data source is explicitly unavailable (missing configuration, stale snapshot,
 * empty upstream, ...). The message carries only a publishable reason and never
 * the upstream exception text, so ARNs, account ids and internal keys cannot leak
 * into the HTTP response.
 */
public class AwsReadUnavailableException extends RuntimeException {

    public AwsReadUnavailableException(String message) {
        super(message);
    }
}
