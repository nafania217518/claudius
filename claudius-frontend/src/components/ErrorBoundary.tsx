import { Component, type ErrorInfo, type ReactNode } from 'react'

// M2: a render-time throw used to blank the whole app. This catches it and
// shows a minimal fallback so the rest of the tree stays interactive.
export default class ErrorBoundary extends Component<
  { children: ReactNode },
  { error: Error | null }
> {
  state: { error: Error | null } = { error: null }

  static getDerivedStateFromError(error: Error): { error: Error } {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error(error, info.componentStack)
  }

  render(): ReactNode {
    if (this.state.error) {
      return (
        <div className="app__error p-6 text-red-600">
          <h1>Something went wrong.</h1>
          <p>{this.state.error.message}</p>
        </div>
      )
    }
    return this.props.children
  }
}
