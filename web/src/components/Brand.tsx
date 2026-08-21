interface BrandMarkProps {
  size?: number
  className?: string
}

export function BrandMark({ size = 28, className }: BrandMarkProps) {
  return (
    <svg
      aria-hidden="true"
      className={className}
      height={size}
      viewBox="0 0 36 36"
      width={size}
    >
      <path
        d="m14 7-9 11 9 11M22 7l9 11-9 11"
        fill="none"
        stroke="currentColor"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="3.6"
      />
      <path
        d="m21 5-6 26"
        fill="none"
        stroke="var(--accent)"
        strokeLinecap="round"
        strokeWidth="3.6"
      />
    </svg>
  )
}

export function BrandWordmark() {
  return (
    <span className="brandWordmark">
      <BrandMark />
      <span className="brandText">
        <span>CodeAgent</span>
        <span className="brandBadge">HARNESS</span>
      </span>
    </span>
  )
}
