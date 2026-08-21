import type { ReactNode } from 'react'
import { X } from 'lucide-react'

interface ModalProps {
  title: string
  children: ReactNode
  onClose: () => void
}

export function Modal({ title, children, onClose }: ModalProps) {
  return (
    <div className="modalBackdrop" role="presentation" onMouseDown={event => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <section
        aria-label={title}
        aria-modal="true"
        className="modalCard"
        role="dialog"
      >
        <header className="modalHeader">
          <h2>{title}</h2>
          <button aria-label="关闭" className="iconButton" onClick={onClose} type="button">
            <X size={18} />
          </button>
        </header>
        <div className="modalBody">{children}</div>
      </section>
    </div>
  )
}
