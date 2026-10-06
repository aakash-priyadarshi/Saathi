type ApiEnvironment = 'development' | 'staging' | 'production';
type ApiPlane = 'combined' | 'public' | 'operational';

export function shouldExposeApiDocs(environment: ApiEnvironment, plane: ApiPlane): boolean {
  return environment === 'development' && plane !== 'public';
}
