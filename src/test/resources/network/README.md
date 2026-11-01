# Local HTTPS fixtures

These keys and certificates are **public test data**, not production credentials.
The fixed password is `bhash-http-test`. Certificates are valid from 2020 to 2040;
`expired.p12` deliberately expired in 2020. `wrong-host.p12` has the wrong DNS SAN.
`untrusted.p12` is independently self-signed and is never trusted by these tests.

`ca.pem` and `trust.p12` contain only the public fixture CA certificate. The CA's
private signing key is not committed. The three CA-signed server keystores and
independently self-signed server keystore contain public, disposable test keys.

JVM client processes select the private fixture truststore explicitly. Linux
client processes select `SSL_CERT_FILE`; native libcurl also honors that setting.
Windows native tests add `ca.pem` to the disposable Server Core container's user
root store before running the relevant batch. No test changes a developer's
Windows certificate store. HTTPS always verifies the chain, host name and dates.
