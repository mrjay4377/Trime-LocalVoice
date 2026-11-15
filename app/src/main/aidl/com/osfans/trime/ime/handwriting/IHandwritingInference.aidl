// 手写推理跨进程接口：IME 主进程（键盘）与 :hw_infer 推理子进程之间的桥梁。
// 把 ORT native 推理隔离在独立进程，即使它在设备上 abort 也只杀子进程，
// 键盘 IME 永远活着——彻底消除「写完手写跳回键盘」。
package com.osfans.trime.ime.handwriting;

interface IHandwritingInference {
    /**
     * 识别一批笔迹，返回 JSON：
     * 成功：{"ok":true,"candidates":[{"text":"一","score":0.9},...]}
     * 失败：{"ok":false,"error":"原因"}
     * strokesJson 格式：{"strokes":[[[x,y],[x,y],...],...]}（每笔为点序列）。
     */
    String recognize(String strokesJson, int topK);
}
