#Requires AutoHotkey v2.0

; ============================================================
; KEYBOARD SHORTCUTS REFERENCE
; ============================================================
;
; APP MANAGEMENT:
;   Alt + Shift + R         - Restart active app (force after 800ms)
;   Alt + Shift + A         - Restart active app as administrator (force after 800ms)
;   Shift + Alt + Q         - Force close active app
;
; WINDOW MINIMIZE/RESTORE:
;   Alt + E/↓         - Minimize window and add to stack
;   Shift + Alt + E/↓ - Restore last minimized window (LIFO)
;
; TASK SWITCHING:
;   Ctrl + ↑        - Open task view (Win + Tab)
;   Alt + Tab       - Open task view (Win + Tab)
;   Ctrl + Win      - Open task view (Win + Tab)
;   Pause           - Open task view (Win + Tab)
;
; SYSTEM:
;   Shift + Ctrl + ↑       - Restart Windows Explorer
;
; MEDIA & NAVIGATION:
;   F1              - Win + H (voice typing)
;   End             - Next track
;   Home            - Previous track
;   Scroll Lock     - Alt + Space
;   Insert          - Ctrl + Alt + V
;   Print Screen    - Win + Shift + S (截图)
;   XButton1        - Volume Down，长按持续减小（鼠标侧键后退）
;   XButton2        - Volume Up，长按持续增大（鼠标侧键前进）
;
; ============================================================

global minimizedStack := []
global windowOrder := []
global currentIndex := 1
global lastUpdateTime := 0


ProcessGetList() {
    pids := []
    for process in ComObjGet("winmgmts:").ExecQuery("Select ProcessId from Win32_Process") {
        pids.Push(process.ProcessId)
    }
    return pids
}

MatchesForceApp(processName, forceApps) {
    for appPattern in forceApps {
        if (InStr(processName, appPattern) = 1)
            return true
    }
    return false
}

IsAppHung(hwnd) {
    return DllCall("IsHungAppWindow", "Ptr", hwnd, "Int")
}


!+r:: {  ; Alt + Shift + R - Restart active app (force after 800ms)
    RestartAppForce(false)
}

!+a:: {  ; Alt + Shift + A - Restart active app as admin (force after 800ms)
    RestartAppForce(true)
}

RestartAppForce(asAdmin := false) {
    try {
        hwnd := WinGetID("A")
        pid  := WinGetPID("ahk_id " hwnd)
        exe  := ProcessGetName(pid)

        if (exe ~= "^(explorer\.exe|dwm\.exe|csrss\.exe|winlogon\.exe)$")
            return

        path := ProcessGetPath(pid)
        forceApps := ["clash", "verge-mihomo"]

        PostMessage 0x10, 0, 0, , hwnd
        Sleep 800

        if (MatchesForceApp(exe, forceApps)) {
            ; @ahk-ignore ProcessGetList
            allPIDs := ProcessGetList()
            for p in allPIDs {
                try {
                    pExe := ProcessGetName(p)
                    if (MatchesForceApp(pExe, forceApps))
                        Run 'taskkill /F /T /PID ' p, , 'Hide'
                }
            }
        } else {
            if (ProcessExist(pid))
                Run 'taskkill /F /T /PID ' pid, , 'Hide'
        }

        if (asAdmin)
            Run path, , "RunAs"
        else
            Run 'explorer.exe "' path '"'
    }
}


!e:: {  ; Alt + E - Minimize and add to stack
    MinimizeWindow()
}


+!q:: {  ; Shift + Alt + Q - Force close active app
    hwnd := WinGetID("A")
    pid := WinGetPID("ahk_id " hwnd)
    exe := ProcessGetName(pid)

    if (InStr(exe, "steam") > 0) {
        try Run("steam://exit", , "Hide")
        Sleep(2000)
        for processName in ["steam.exe", "steamwebhelper.exe", "steamservice.exe", "SteamService.exe"]
            while ProcessExist(processName)
                try ProcessClose(processName)
        allPIDs := ProcessGetList()
        for p in allPIDs {
            try {
                if (InStr(ProcessGetName(p), "steam") > 0)
                    ProcessClose(p)
            }
        }
        return
    }

    if (MatchesForceApp(exe, ["clash", "verge-mihomo"])) {
        allPIDs := ProcessGetList()
        for p in allPIDs {
            try {
                if (MatchesForceApp(ProcessGetName(p), ["clash", "verge-mihomo"]))
                    ProcessClose(p)
            }
        }
        return
    }

    if (IsAppHung(hwnd)) {
        RunWait 'taskkill /F /T /PID ' pid, , 'Hide'
    } else {
        PostMessage 0x10, 0, 0, , hwnd
        Sleep 500
        if (ProcessExist(pid))
            RunWait 'taskkill /F /T /PID ' pid, , 'Hide'
    }
}


+!e:: {  ; Shift + Alt + E - Restore last minimized window (LIFO)
    RestoreWindow()
}

^LWin:: {
    Send "#{Tab}"
}

^RWin:: {
    Send "#{Tab}"
}

LWin & Ctrl:: {
    Send "#{Tab}"
}

RWin & Ctrl:: {
    Send "#{Tab}"
}

^Up:: {
    Send "#{Tab}"
}

!Tab:: {
    Send "#{Tab}"
}

!Down:: {
    MinimizeWindow()
}

>^Down:: {
    MinimizeWindow()
}

+!Down:: {
    RestoreWindow()
}

>^+Down:: {
    RestoreWindow()
}

+^Up:: {  ; Shift + Ctrl + Up Arrow - Restart Explorer
    while ProcessExist("explorer.exe")
        ProcessClose("explorer.exe")
    Sleep(200)
    Run("explorer.exe")
    Return
}

>^e:: {
    MinimizeWindow()
}

>^+e:: {
    RestoreWindow()
}

; ============================================================
; MEDIA & NAVIGATION SHORTCUTS
; ============================================================
Ins:: {
    Send "^!v"
}
F1:: {
    Send "#{h}"
}

End:: {
    Send "{Media_Next}"
}

Home:: {
    Send "{Media_Prev}"
}

Pause:: {
    Send "#{Tab}"
}

ScrollLock:: {
    Send "!{Space}"
}

PrintScreen:: {
    Send "#+s"
}

; ============================================================
; MOUSE SIDE BUTTONS - VOLUME CONTROL (with long-press repeat)
;
;   单击：调整一格音量
;   长按：400ms 后开始持续调整，每 80ms 重复一次
; ============================================================

XButton1:: {
    Send "{Volume_Down}"
    SetTimer(VolDown, -400)
}
XButton1 up:: {
    SetTimer(VolDown, 0)
}

XButton2:: {
    Send "{Volume_Up}"
    SetTimer(VolUp, -400)
}
XButton2 up:: {
    SetTimer(VolUp, 0)
}

VolDown() {
    if !GetKeyState("XButton1", "P") {
        SetTimer(VolDown, 0)
        return
    }
    Send "{Volume_Down}"
    SetTimer(VolDown, 80)
}

VolUp() {
    if !GetKeyState("XButton2", "P") {
        SetTimer(VolUp, 0)
        return
    }
    Send "{Volume_Up}"
    SetTimer(VolUp, 80)
}

; ============================================================
; HELPER FUNCTIONS
; ============================================================

MinimizeWindow() {
    global minimizedStack
    try {
        winID := WinGetID("A")
        WinMinimize "A"
        minimizedStack.Push(winID)
    }
}

RestoreWindow() {
    global minimizedStack

    while (minimizedStack.Length > 0) {
        winID := minimizedStack.Pop()
        try {
            if (WinExist("ahk_id " winID)) {
                WinRestore "ahk_id " winID
                WinActivate "ahk_id " winID
                return
            }
        }
    }

    Send "!{Tab}"
}

CycleWindows(direction) {
    global windowOrder, currentIndex, lastUpdateTime

    currentTime := A_TickCount
    if (windowOrder.Length = 0 || (currentTime - lastUpdateTime) > 2000) {
        UpdateWindowOrder()
        lastUpdateTime := currentTime
    }

    if (windowOrder.Length = 0)
        return

    validWindows := []
    for winID in windowOrder {
        if (WinExist("ahk_id " winID)) {
            validWindows.Push(winID)
        }
    }
    windowOrder := validWindows

    if (windowOrder.Length = 0)
        return

    try {
        activeID := WinGetID("A")
        found := false

        Loop windowOrder.Length {
            if (windowOrder[A_Index] = activeID) {
                currentIndex := A_Index
                found := true
                break
            }
        }

        if (!found)
            currentIndex := 1
    }

    currentIndex += direction

    if (currentIndex > windowOrder.Length)
        currentIndex := 1
    else if (currentIndex < 1)
        currentIndex := windowOrder.Length

    try {
        targetID := windowOrder[currentIndex]
        if (WinExist("ahk_id " targetID)) {
            WinActivate "ahk_id " targetID
        } else {
            UpdateWindowOrder()
            if (currentIndex > windowOrder.Length)
                currentIndex := 1
            WinActivate "ahk_id " windowOrder[currentIndex]
        }
    }
}

UpdateWindowOrder() {
    global windowOrder

    windowInfo := []
    ids := WinGetList(,, "Program Manager")

    for id in ids {
        try {
            if (WinExist("ahk_id " id) && WinGetTitle("ahk_id " id) != "") {
                pid := WinGetPID("ahk_id " id)
                windowInfo.Push({id: id, pid: pid})
            }
        }
    }

    n := windowInfo.Length
    Loop n - 1 {
        i := A_Index
        Loop n - i {
            j := A_Index
            if (windowInfo[j].pid > windowInfo[j + 1].pid) {
                temp := windowInfo[j]
                windowInfo[j] := windowInfo[j + 1]
                windowInfo[j + 1] := temp
            }
        }
    }

    windowOrder := []
    for info in windowInfo {
        windowOrder.Push(info.id)
    }
}
