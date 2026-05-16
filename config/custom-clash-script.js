allow - lan: false
mixed - port: 7890

ipv6: true
mode: rule
dns:
enable: true
ipv6: true
prefer - h3: true
listen: 127.0.0.1: 53
enhanced - mode: fake - ip
fake - ip - range: 198.18.0.1 / 16
cache - algorithm: arc
use - hosts: false
use - system - hosts: false
fake - ip - filter:
- "rule-set:fakeip-filter"
    - "127.0.0.1"
    - "::1"
  default -nameserver:
- 223.5.5.5
    - 119.29.29.29
        - 1.1.1.1
            - 8.8.8.8
                - 9.9.9.9
                    - tls://1.0.0.1:853

nameserver:
- https://1.1.1.1/dns-query
- https://8.8.8.8/dns-query
- https://9.9.9.9/dns-query
- tls://1.0.0.1:853

direct - nameserver:
- https://dns.alidns.com/dns-query
- https://120.53.53.53/dns-query
- tls://119.29.29.29:853
direct - nameserver - follow - policy: true


tun:
enable: true
stack: mixed # mixed # gvisor # system
device: newsilence
mtu: 1500
udp - timeout: 300
endpoint - independent - nat: true
auto - route: true
auto - redirect: true
auto - detect - interface: true
dns - hijack:
- any: 53
    - tcp://any:53
- udp://any:53
strict - route: true

geodata - mode: false

sniffer:
enable: true
force - dns - mapping: true
parse - pure - ip: true
sniff:
HTTP:
ports: [80, 8080 - 8880]
override - destination: true
TLS:
ports: [443, 8443]
override - destination: true
QUIC:
ports: [443, 8443]
skip - domain:
- "Mijia Cloud"

profile:
store - selected: false
store - fake - ip: true

find - process - mode: always
tcp - concurrent: true
unified - delay: true
keep - alive - interval: 5
keep - alive - idle: 5
disable - keep - alive: false
etag - support: true
