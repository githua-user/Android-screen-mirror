#!/usr/bin/env python3
"""
屏幕镜像接收端 - 接收Android端发送的RGB帧数据
"""
import socket
import struct
import threading
import numpy as np
import cv2
import sys
import time
from typing import Optional

CMD_TAP = 0x10
CMD_SWIPE = 0x11
CMD_BACK = 0x12

DRAG_THRESHOLD = 0.3  # 归一化位移阈值，超过视为拖动
LONG_PRESS_SECONDS = 0.5  # 按住超过该时长视为长按
CLICK_DURATION_MS = 60


class ScreenReceiver:
    """屏幕镜像接收端"""

    def __init__(self, host: str = "0.0.0.0", port: int = 8888):
        self.host = host
        self.port = port
        self.socket: Optional[socket.socket] = None
        self.client: Optional[socket.socket] = None
        self.last_w = -1
        self.last_h = -1
        cv2.namedWindow("video_receiver", cv2.WINDOW_NORMAL)
        cv2.resizeWindow("video_receiver", 720, 1280)
        #cv2.namedWindow("video_receiver", cv2.WINDOW_AUTOSIZE)
        
        self.times=0
        self.cur_frame = None       # 当前显示帧
        self.press_norm = None      # 左键按下时的归一化坐标
        self.press_time = 0.0
        self.dragged = False
        cv2.setMouseCallback("video_receiver", self._on_mouse)
     

    def start(self):
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 2)
        self.socket.bind((self.host, self.port))
        self.socket.listen(1)

        print(f"📡 服务启动: {self.host}:{self.port}")
        print("等待Android端连接...")

        self.client, addr = self.socket.accept()
        print(f"✅ 已连接: {addr}")

        # 设置接收缓冲区
        self.client.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 2 * 1024 * 1024)
        
        threading.Thread(target=self._receive_loop, daemon=True).start()   # 子线程
        self._ui_loop()    

    def _receive_loop(self):
        buffer = bytearray()
        while 1:
            # 接收数据
            chunk = self.client.recv(65536)
            if not chunk:
                print("⚠️ 连接断开")
                self.client=None
                break
            buffer.extend(chunk)
            # 处理缓冲区中的数据包
            while len(buffer) >= 5:  # 至少需要包头
                packet_type = buffer[0]
                if packet_type == 0x00:  # 屏幕信息
                    if len(buffer) >= 9:  # 1 + 4 + 4
                        data = buffer[1:9]
                        self.screen_width = struct.unpack(">I", data[0:4])[0]
                        self.screen_height = struct.unpack(">I", data[4:8])[0]
                        print(f"📺 屏幕信息: {self.screen_width}x{self.screen_height}")
                        buffer = buffer[9:]
                        continue
                    else:
                        break
                elif packet_type == 0x01:  # 视频帧竖
                    if len(buffer) >= 5:
                        data_len = struct.unpack(">I", buffer[1:5])[0]
                        total_size = 5 + data_len

                        if len(buffer) >= total_size:
                            frame_data = buffer[5:total_size]
                            self._process_frame(frame_data)
                            buffer = buffer[total_size:]
                            if self.screen_width > self.screen_height:
                                temp = self.screen_width
                                self.screen_width = self.screen_height
                                self.screen_height = temp
                        else:
                            break
                    else:
                        break
                elif packet_type == 0x02:  # 视频帧横
                    if len(buffer) >= 5:
                        data_len = struct.unpack(">I", buffer[1:5])[0]
                        total_size = 5 + data_len

                        if len(buffer) >= total_size:
                            frame_data = buffer[5:total_size]
                            self._process_frame(frame_data)
                            buffer = buffer[total_size:]
                            if self.screen_width < self.screen_height:
                                temp = self.screen_width
                                self.screen_width = self.screen_height
                                self.screen_height = temp
                        else:
                            break
                    else:
                        break
                else:
                    print(f"数据序列有误")
                    self.client=None
                    break
          
       
    def _process_frame(self, frame_data: bytes):
        img = cv2.imdecode(np.frombuffer(frame_data, dtype=np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            print("⚠️ JPEG 解码失败")
            return

        h, w = img.shape[:2]
        if w != self.last_w or h != self.last_h:
            if self.times:
                cv2.resizeWindow("video_receiver", w, h)
                print("222")
            self.times+=1
            self.last_w = w
            self.last_h = h
        self.cur_frame = img

    def _ui_loop(self):
         while 1:
             if self.cur_frame is not None:
                 cv2.imshow("video_receiver", self.cur_frame)
             cv2.waitKey(1)
             if not self.client:
                 self.stop()
                 break
                    
    def send_cmd(self, cmd: int, payload: bytes):
        if self.client is None:
            return
        try:
            self.client.sendall(
                struct.pack(">B", cmd) + struct.pack(">I", len(payload)) + payload
            )
        except Exception as e:
            print(f"指令发送失败: {e}")
    
    def _on_mouse(self, event, mx, my, flags, param): #按
        if event == cv2.EVENT_LBUTTONDOWN:
            self.press_norm = self._to_norm(mx, my)
           
            self.press_time = time.time()
            self.dragged = False

        elif event == cv2.EVENT_MOUSEMOVE and (flags & cv2.EVENT_FLAG_LBUTTON):  #拖
            cur = self._to_norm(mx, my)
            if self.press_norm is not None:
                if abs(cur[0] - self.press_norm[0]) > DRAG_THRESHOLD or \
                abs(cur[1] - self.press_norm[1]) > DRAG_THRESHOLD:
                    self.dragged = True

        elif event == cv2.EVENT_LBUTTONUP:   #松开
            if self.press_norm is None:
                return
            cur = self._to_norm(mx, my)
            dt = time.time() - self.press_time

            if self.dragged:            # 拖动 
                dur = int(min(max(dt * 1000, 100), 1500))
                self.send_cmd(CMD_SWIPE, struct.pack(
                    '>ffffi',
                    self.press_norm[0], self.press_norm[1], cur[0], cur[1], dur))
            elif dt >= LONG_PRESS_SECONDS:   #  长按
                dur = int(min(dt * 1000, 2000))
                self.send_cmd(CMD_TAP, struct.pack(
                    '>ffi', self.press_norm[0], self.press_norm[1], dur))
            else:                       # 普通点击
                self.send_cmd(CMD_TAP, struct.pack(
                    '>ffi', cur[0], cur[1], CLICK_DURATION_MS))

        elif event == cv2.EVENT_RBUTTONDOWN:    # 右键 = 返回键
            self.send_cmd(CMD_BACK, struct.pack('>i',1))
    
    def _to_norm(self, mx: float, my: float):   #转为图像坐标并归一
        if self.cur_frame is None:
            return (0.0, 0.0)
        img_h, img_w = self.cur_frame.shape[:2]
        _,_,win_w, win_h = cv2.getWindowImageRect("video_receiver")
        scale = min(win_w / img_w, win_h / img_h)
        disp_w, disp_h = img_w * scale, img_h * scale
        off_x, off_y = (win_w - disp_w) / 2.0, (win_h - disp_h) / 2.0
        nx = (mx - off_x) / disp_w
        ny = (my - off_y) / disp_h
        return (min(max(nx, 0.0), 1.0), min(max(ny, 0.0), 1.0))  

    def stop(self):
        if self.client:
            self.client.close()
            self.client = None
        if self.socket:
            self.socket.close()
            self.socket = None
        cv2.destroyAllWindows()
        print("👋 服务已停止")

def main():
    receiver = ScreenReceiver(host="0.0.0.0", port=8888)
    receiver.start()

if __name__ == "__main__":
    main()
