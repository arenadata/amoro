export type DateInput = Date | number | string | undefined | null

export function toDate(input: DateInput): Date | undefined {
  if (input == null) return undefined
  if (input instanceof Date) return input
  if (typeof input === 'number') return new Date(input)

  const d = new Date(input)
  return Number.isNaN(d.getTime()) ? undefined : d
}

export function toYMD(date?: Date): string | null {
  if (!date) return null
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

export function addDays(date: Date, days: number): Date {
  return new Date(date.getTime() + days * 24 * 60 * 60 * 1000)
}
