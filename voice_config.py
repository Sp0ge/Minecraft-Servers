"""Validate the address advertised by the official Voice Chat proxy."""
import ipaddress
from urllib.parse import urlsplit

def public_host(value,port):
 port=int(port)
 if not 1024<=port<=65535:raise ValueError('VOICE_PUBLIC_PORT must be 1024..65535')
 value=value.strip()
 if not value:return str(port)
 if value.isdigit():
  if int(value)!=port:raise ValueError('VOICE_PUBLIC_HOST port differs from VOICE_PUBLIC_PORT')
  return str(port)
 if any(c.isspace() for c in value) or any(c in value for c in '/\\?#=@'):
  raise ValueError('VOICE_PUBLIC_HOST must be a hostname/IP, without URL or credentials')
 try:
  address=ipaddress.ip_address(value)
 except ValueError:address=None
 if address is not None:
  return ('['+str(address)+']' if address.version==6 else str(address))+':'+str(port)
 parsed=urlsplit('//'+value)
 host=parsed.hostname
 if not host or parsed.username is not None:raise ValueError('Invalid VOICE_PUBLIC_HOST')
 if parsed.port is not None and parsed.port!=port:raise ValueError('Advertised voice port differs from published port')
 if ':' in host:ipaddress.IPv6Address(host);host='['+host+']'
 else:
  host=host.encode('idna').decode('ascii')
  if not all(part and len(part)<=63 and part[0]!='-' and part[-1]!='-' and all(c.isalnum() or c=='-' for c in part) for part in host.rstrip('.').split('.')):
   raise ValueError('Invalid voice hostname')
 return host+':'+str(port)
