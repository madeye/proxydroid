#!/usr/bin/env python3
"""
Simple HTTP CONNECT proxy for testing ProxyDroid VPN mode.
Logs all CONNECT requests and can optionally require authentication.
"""

import argparse
import base64
import socket
import threading
import sys
import os

class HttpProxy:
    def __init__(self, host='0.0.0.0', port=8888, username=None, password=None):
        self.host = host
        self.port = port
        self.username = username
        self.password = password
        self.server_socket = None
        self.running = False
        self.connections = []
        self.lock = threading.Lock()

    def start(self):
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind((self.host, self.port))
        self.server_socket.listen(100)
        self.running = True
        print(f"HTTP Proxy started on {self.host}:{self.port}")
        if self.username:
            print(f"Authentication required: {self.username}:***")

        while self.running:
            try:
                client_socket, addr = self.server_socket.accept()
                thread = threading.Thread(target=self.handle_client, args=(client_socket, addr))
                thread.daemon = True
                thread.start()
            except socket.error:
                break

    def handle_client(self, client_socket, addr):
        try:
            client_socket.settimeout(30)
            request = b''
            while b'\r\n\r\n' not in request:
                chunk = client_socket.recv(4096)
                if not chunk:
                    return
                request += chunk

            request_str = request.decode('utf-8', errors='ignore')
            lines = request_str.split('\r\n')
            first_line = lines[0]

            print(f"[{addr[0]}:{addr[1]}] {first_line}")

            # Check authentication if required
            if self.username:
                auth_header = None
                for line in lines:
                    if line.lower().startswith('proxy-authorization:'):
                        auth_header = line.split(':', 1)[1].strip()
                        break

                if not auth_header:
                    client_socket.send(b'HTTP/1.1 407 Proxy Authentication Required\r\n'
                                      b'Proxy-Authenticate: Basic realm="Proxy"\r\n\r\n')
                    return

                try:
                    auth_type, auth_data = auth_header.split(' ', 1)
                    if auth_type.lower() != 'basic':
                        client_socket.send(b'HTTP/1.1 407 Proxy Authentication Required\r\n\r\n')
                        return
                    decoded = base64.b64decode(auth_data).decode('utf-8')
                    user, passwd = decoded.split(':', 1)
                    if user != self.username or passwd != self.password:
                        print(f"  Auth failed: {user}:***")
                        client_socket.send(b'HTTP/1.1 407 Proxy Authentication Required\r\n\r\n')
                        return
                    print(f"  Auth success: {user}")
                except Exception as e:
                    print(f"  Auth error: {e}")
                    client_socket.send(b'HTTP/1.1 407 Proxy Authentication Required\r\n\r\n')
                    return

            # Handle CONNECT request
            if first_line.startswith('CONNECT '):
                self.handle_connect(client_socket, first_line)
            else:
                # For non-CONNECT requests, just return 400
                client_socket.send(b'HTTP/1.1 400 Bad Request\r\n\r\n')

        except Exception as e:
            print(f"Error handling client: {e}")
        finally:
            try:
                client_socket.close()
            except:
                pass

    def handle_connect(self, client_socket, first_line):
        try:
            # Parse CONNECT host:port
            parts = first_line.split()
            if len(parts) < 2:
                client_socket.send(b'HTTP/1.1 400 Bad Request\r\n\r\n')
                return

            target = parts[1]
            if ':' in target:
                host, port = target.rsplit(':', 1)
                port = int(port)
            else:
                host = target
                port = 443

            # Log the connection
            with self.lock:
                self.connections.append((host, port))

            # Connect to target
            try:
                target_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                target_socket.settimeout(30)
                target_socket.connect((host, port))
            except socket.error as e:
                print(f"  Failed to connect to {host}:{port}: {e}")
                client_socket.send(b'HTTP/1.1 502 Bad Gateway\r\n\r\n')
                return

            # Send success response
            client_socket.send(b'HTTP/1.1 200 Connection Established\r\n\r\n')
            print(f"  Connected to {host}:{port}")

            # Relay data
            self.relay(client_socket, target_socket)

        except Exception as e:
            print(f"  CONNECT error: {e}")

    def relay(self, client_socket, target_socket):
        client_socket.setblocking(False)
        target_socket.setblocking(False)

        sockets = [client_socket, target_socket]
        try:
            import select
            while True:
                readable, _, exceptional = select.select(sockets, [], sockets, 30)

                if exceptional:
                    break

                if not readable:
                    break

                for sock in readable:
                    try:
                        data = sock.recv(65536)
                        if not data:
                            return

                        if sock is client_socket:
                            target_socket.send(data)
                        else:
                            client_socket.send(data)
                    except socket.error:
                        return
        except Exception as e:
            pass
        finally:
            try:
                target_socket.close()
            except:
                pass

    def stop(self):
        self.running = False
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass

    def get_connections(self):
        with self.lock:
            return list(self.connections)


def main():
    parser = argparse.ArgumentParser(description='Simple HTTP CONNECT Proxy for testing')
    parser.add_argument('--host', default='0.0.0.0', help='Host to bind to')
    parser.add_argument('--port', type=int, default=8888, help='Port to listen on')
    parser.add_argument('--username', help='Username for proxy authentication')
    parser.add_argument('--password', help='Password for proxy authentication')
    args = parser.parse_args()

    proxy = HttpProxy(args.host, args.port, args.username, args.password)
    try:
        proxy.start()
    except KeyboardInterrupt:
        print("\nShutting down...")
        proxy.stop()


if __name__ == '__main__':
    main()
