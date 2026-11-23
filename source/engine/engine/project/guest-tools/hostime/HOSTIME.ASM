; FreeDOS Pocket host-text receiver. SPDX-License-Identifier: MIT
; Receives one byte at a time on COM1, puts it in the BIOS keyboard buffer,
; then echoes that byte as an acknowledgement. NASM: nasm -f bin -o HOSTIME.COM

bits 16
org 100h

start:
    jmp install

old_int1c: dd 0

timer_handler:
    jmp short timer_body
    db 'FDHI'                    ; installation signature at vector+2
timer_body:
    push ax
    push bx
    push cx
    push dx
    push ds
    push es

    ; Do not consume a serial byte unless the BIOS keyboard ring has room.
    mov ax,40h
    mov es,ax
    mov bx,[es:1ch]              ; current tail
    mov cx,bx
    add cx,2
    cmp cx,[es:82h]              ; enhanced BIOS buffer end
    jb .next_ready
    mov cx,[es:80h]              ; wrap to buffer start
.next_ready:
    cmp cx,[es:1ah]              ; next tail would equal head: full
    je .chain

    mov dx,3fdh                  ; COM1 line status
    in al,dx
    test al,1                    ; data ready
    jz .chain
    mov dx,3f8h
    in al,dx
    or al,al
    jz .ack_byte                 ; zero is a host handshake, not guest text
    mov [es:bx],al               ; ASCII byte, scan code zero
    mov byte [es:bx+1],0
    mov [es:1ch],cx

    ; Echo the accepted byte. A host waits for this before sending the next.
.ack_byte:
    mov ah,al
    mov dx,3fdh
    mov cx,64
.tx_wait:
    in al,dx
    test al,20h                  ; transmitter holding register empty
    jnz .tx_ready
    loop .tx_wait
    jmp short .chain             ; keyboard byte is already committed
.tx_ready:
    mov dx,3f8h
    mov al,ah
    out dx,al

.chain:
    pop es
    pop ds
    pop dx
    pop cx
    pop bx
    pop ax
    jmp far [cs:old_int1c]

install:
    push cs
    pop ds
    mov ax,351ch
    int 21h                      ; ES:BX = current INT 1Ch
    cmp byte [es:bx+2],'F'
    jne .not_installed
    cmp byte [es:bx+3],'D'
    jne .not_installed
    cmp byte [es:bx+4],'H'
    jne .not_installed
    cmp byte [es:bx+5],'I'
    jne .not_installed
    mov dx,already_msg
    mov ah,9
    int 21h
    mov ax,4c00h
    int 21h

.not_installed:
    mov [old_int1c],bx
    mov [old_int1c+2],es

    ; 115200 baud, 8 data bits, no parity, one stop bit, FIFO enabled.
    cli
    mov dx,3f9h                  ; interrupt enable
    xor al,al
    out dx,al
    mov dx,3fbh                  ; divisor latch access
    mov al,80h
    out dx,al
    mov dx,3f8h                  ; divisor low = 1
    mov al,1
    out dx,al
    inc dx                       ; divisor high = 0
    xor al,al
    out dx,al
    mov dx,3fbh
    mov al,3                     ; 8N1
    out dx,al
    mov dx,3fah
    mov al,0c7h                  ; enable and clear 16550 FIFOs
    out dx,al
    mov dx,3fch
    mov al,0bh                   ; DTR, RTS, OUT2
    out dx,al
    ; Drop any byte left by a client that timed out before this TSR existed.
.flush_rx:
    mov dx,3fdh
    in al,dx
    test al,1
    jz .flushed
    mov dx,3f8h
    in al,dx
    jmp short .flush_rx
.flushed:
    sti

    mov dx,timer_handler
    mov ax,251ch
    int 21h
    mov dx,installed_msg
    mov ah,9
    int 21h
    mov dx,(resident_end-$$+100h+15)/16 ; paragraphs from PSP
    mov ax,3100h
    int 21h

already_msg:   db 'HOSTIME is already installed.',13,10,'$'
installed_msg: db 'HOSTIME installed: COM1 115200, GB2312/raw byte bridge.',13,10,'$'
resident_end:

